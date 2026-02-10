package radisson.actors.backend

import scala.concurrent.duration._

import io.circe.Codec
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import radisson.backend.{MemoryParser, PortAllocator}
import radisson.config.{AppConfig, BackendConfig}
import radisson.util.Logging

object LlamaBackendSupervisor extends Logging {

  enum Command {
    case Initialize(config: AppConfig)
    case RequestBackend(backendId: String, replyTo: ActorRef[BackendResponse])
    case StartBackend(backendId: String, replyTo: ActorRef[BackendResponse])
    case StopBackend(backendId: String)
    case BackendStarted(
        backendId: String,
        port: Int,
        runner: ActorRef[LlamaBackendRunner.Command]
    )
    case BackendFailed(backendId: String, reason: String)
    case BackendStopped(backendId: String)
    case RegisterDispatcher(
        dispatcher: ActorRef[
          radisson.actors.completion.CompletionRequestDispatcher.Command
        ]
    )
    case RegisterEmbeddingDispatcher(
        dispatcher: ActorRef[
          radisson.actors.embedding.EmbeddingRequestDispatcher.Command
        ]
    )
    case BackendDrained(backendId: String)
    case AcquireHold(
        backendId: String,
        ttlSeconds: Int,
        replyTo: ActorRef[HoldResponse]
    )
    case ReleaseHold(
        backendId: String,
        replyTo: ActorRef[HoldResponse]
    )
    case CleanExpiredHolds
    case GetStatus(replyTo: ActorRef[StatusResponse])
  }

  enum BackendResponse {
    case Available(endpoint: String, port: Int)
    case Starting
    case Failed(reason: String)
  }

  enum HoldResponse {
    case Acquired(
        backendId: String,
        backendType: String,
        status: String,
        port: Int,
        endpoint: String,
        acquiredAt: Long,
        expiresAt: Long
    )
    case Released(backendId: String, releasedAt: Long)
    case NotFound(backendId: String)
    case InsufficientMemory(backendId: String, requiredMemory: Long)
    case Failed(backendId: String, reason: String)
  }

  case class BackendHold(
      backendId: String,
      acquiredAt: Long,
      expiresAt: Long
  )

  case class BackendStatus(
      id: String,
      backend_type: String,
      state: String, // "running", "starting", "draining", "stopped"
      port: Option[Int] = None,
      memory_bytes: Option[Long] = None,
      last_access_time: Option[Long] = None,
      held: Boolean = false,
      hold_expires_at: Option[Long] = None
  ) derives Codec.AsObject

  case class StatusResponse(
      total_memory_bytes: Long,
      used_memory_bytes: Long,
      available_memory_bytes: Long,
      backends: List[BackendStatus],
      pending_starts: Int
  ) derives Codec.AsObject

  case class RunningBackend(
      port: Int,
      runner: ActorRef[LlamaBackendRunner.Command],
      memoryUsed: Long,
      lastAccessTime: Long
  )

  case class StartingBackend(
      memoryReserved: Long,
      replyTo: ActorRef[BackendResponse],
      runner: ActorRef[LlamaBackendRunner.Command],
      port: Int,
      pendingEviction: Boolean = false
  )

  case class DrainingBackend(
      port: Int,
      runner: ActorRef[LlamaBackendRunner.Command],
      memoryUsed: Long,
      drainingStartedAt: Long
  )

  case class PendingStart(
      backendConfig: BackendConfig,
      requiredMemory: Long,
      replyTo: ActorRef[BackendResponse]
  )

  case class SupervisorState(
      config: AppConfig,
      totalMemory: Long,
      runningBackends: Map[String, RunningBackend],
      startingBackends: Map[String, StartingBackend],
      drainingBackends: Map[String, DrainingBackend],
      allocatedPorts: Set[Int],
      dispatcherRef: Option[
        ActorRef[radisson.actors.completion.CompletionRequestDispatcher.Command]
      ],
      embeddingDispatcherRef: Option[
        ActorRef[radisson.actors.embedding.EmbeddingRequestDispatcher.Command]
      ],
      pendingStarts: List[PendingStart],
      heldBackends: Map[String, BackendHold]
  )

  def behavior: Behavior[Command] = Behaviors.withTimers { timers =>
    Behaviors.setup { context =>
      def uninitialized(): Behavior[Command] = Behaviors.receiveMessage {
        case Command.Initialize(config) =>
          log.info("Initializing LlamaBackendSupervisor with config")

          val totalMemory = MemoryParser
            .parseMemoryString(config.resources.total_memory)
            .getOrElse {
              log.error(
                "Failed to parse total_memory: {}, defaulting to 1GB",
                config.resources.total_memory
              )
              1024L * 1024L * 1024L
            }

          log.info(
            "Total memory available: {} bytes ({} MB)",
            totalMemory,
            totalMemory / (1024 * 1024)
          )

          val initialState = SupervisorState(
            config = config,
            totalMemory = totalMemory,
            runningBackends = Map.empty,
            startingBackends = Map.empty,
            drainingBackends = Map.empty,
            allocatedPorts = Set.empty,
            dispatcherRef = None,
            embeddingDispatcherRef = None,
            pendingStarts = List.empty,
            heldBackends = Map.empty
          )

          timers.startTimerAtFixedRate(Command.CleanExpiredHolds, 30.seconds)

          active(initialState)

        case _ =>
          log.warn("Received message before initialization")
          Behaviors.same
      }

    def active(state: SupervisorState): Behavior[Command] =
      Behaviors.receiveMessage {
        case Command.RequestBackend(backendId, replyTo) =>
          state.runningBackends.get(backendId) match {
            case Some(backend) =>
              state.config.backends.find(_.id == backendId) match {
                case Some(backendConfig) =>
                  val endpoint = backendConfig.upstream_url.getOrElse(
                    s"http://127.0.0.1:${backend.port}"
                  )

                  log.info(
                    "Backend {} already running, endpoint: {}",
                    backendId,
                    endpoint
                  )
                  val updatedBackend =
                    backend.copy(lastAccessTime = System.currentTimeMillis())
                  replyTo ! BackendResponse.Available(endpoint, backend.port)
                  active(
                    state.copy(
                      runningBackends =
                        state.runningBackends.updated(backendId, updatedBackend)
                    )
                  )

                case None =>
                  log.warn("Backend config not found for {}", backendId)
                  replyTo ! BackendResponse.Failed(
                    s"Backend $backendId not found in configuration"
                  )
                  Behaviors.same
              }

            case None =>
              state.config.backends.find(_.id == backendId) match {
                case Some(backendConfig) if backendConfig.`type` == "local" =>
                  handleLocalBackendRequest(state, backendConfig, replyTo)

                case Some(backendConfig)
                    if backendConfig.`type` == "local-embeddings" =>
                  handleLocalBackendRequest(state, backendConfig, replyTo)

                case Some(backendConfig) if backendConfig.`type` == "remote" =>
                  backendConfig.endpoint match {
                    case Some(endpoint) =>
                      log.info(
                        "Remote backend {} available at {}",
                        backendId,
                        endpoint
                      )
                      replyTo ! BackendResponse.Available(endpoint, 0)
                      Behaviors.same

                    case None =>
                      log.error("Remote backend {} missing endpoint", backendId)
                      replyTo ! BackendResponse.Failed(
                        "Remote backend missing endpoint"
                      )
                      Behaviors.same
                  }

                case None =>
                  log.error("Backend {} not found in configuration", backendId)
                  replyTo ! BackendResponse.Failed(
                    s"Backend $backendId not found"
                  )
                  Behaviors.same

                case _ =>
                  log.error("Backend {} has unknown type", backendId)
                  replyTo ! BackendResponse.Failed("Unknown backend type")
                  Behaviors.same
              }
          }

        case Command.BackendStarted(backendId, port, runner) =>
          state.startingBackends.get(backendId) match {
            case Some(starting) =>
              state.config.backends.find(_.id == backendId) match {
                case Some(backendConfig) =>
                  val endpoint = backendConfig.upstream_url.getOrElse(
                    s"http://127.0.0.1:$port"
                  )

                  if (starting.pendingEviction) {
                    log.info(
                      "Backend {} started but pending eviction, transitioning to draining after trigger request",
                      backendId
                    )

                    starting.replyTo ! BackendResponse.Available(endpoint, port)

                    val drainingBackend = DrainingBackend(
                      port = port,
                      runner = runner,
                      memoryUsed = starting.memoryReserved,
                      drainingStartedAt = System.currentTimeMillis()
                    )

                    state.dispatcherRef.foreach { dispatcher =>
                      dispatcher ! radisson.actors.completion.CompletionRequestDispatcher.Command
                        .BeginDraining(backendId)
                    }

                    active(
                      state.copy(
                        startingBackends = state.startingBackends - backendId,
                        drainingBackends =
                          state.drainingBackends + (backendId -> drainingBackend)
                      )
                    )
                  } else {
                    log.info(
                      "Backend {} successfully started, endpoint: {}",
                      backendId,
                      endpoint
                    )

                    val runningBackend = RunningBackend(
                      port = port,
                      runner = runner,
                      memoryUsed = starting.memoryReserved,
                      lastAccessTime = System.currentTimeMillis()
                    )

                    starting.replyTo ! BackendResponse.Available(endpoint, port)

                    active(
                      state.copy(
                        runningBackends =
                          state.runningBackends + (backendId -> runningBackend),
                        startingBackends = state.startingBackends - backendId
                      )
                    )
                  }

                case None =>
                  log.warn("Backend config not found for {}", backendId)
                  Behaviors.same
              }

            case None =>
              log.warn(
                "Received BackendStarted for unknown backend {}",
                backendId
              )
              Behaviors.same
          }

        case Command.BackendFailed(backendId, reason) =>
          state.startingBackends.get(backendId) match {
            case Some(starting) =>
              log.error("Backend {} failed to start: {}", backendId, reason)

              if (!starting.pendingEviction) {
                starting.replyTo ! BackendResponse.Failed(reason)
              } else {
                log.info("Backend {} failed during pending eviction", backendId)
              }

              context.stop(starting.runner)

              active(
                state.copy(
                  startingBackends = state.startingBackends - backendId,
                  allocatedPorts = state.allocatedPorts - starting.port
                )
              )

            case None =>
              log.warn(
                "Received BackendFailed for unknown backend {}",
                backendId
              )
              Behaviors.same
          }

        case Command.BackendStopped(backendId) =>
          state.runningBackends.get(backendId) match {
            case Some(backend) =>
              log.info("Backend {} stopped", backendId)

              active(
                state.copy(
                  runningBackends = state.runningBackends - backendId,
                  allocatedPorts = state.allocatedPorts - backend.port
                )
              )

            case None =>
              log.warn(
                "Received BackendStopped for unknown backend {}",
                backendId
              )
              Behaviors.same
          }

        case Command.StopBackend(backendId) =>
          state.runningBackends.get(backendId) match {
            case Some(backend) =>
              log.info("Stopping backend {}", backendId)
              backend.runner ! LlamaBackendRunner.Command.Stop
              Behaviors.same

            case None =>
              log.warn("Cannot stop backend {}: not running", backendId)
              Behaviors.same
          }

        case Command.RegisterDispatcher(dispatcher) =>
          log.info("Dispatcher registered with backend supervisor")
          active(state.copy(dispatcherRef = Some(dispatcher)))

        case Command.RegisterEmbeddingDispatcher(dispatcher) =>
          log.info("Embedding dispatcher registered with backend supervisor")
          active(state.copy(embeddingDispatcherRef = Some(dispatcher)))

        case Command.BackendDrained(backendId) =>
          state.drainingBackends.get(backendId) match {
            case Some(backend) =>
              log.info("Backend {} fully drained, stopping", backendId)
              backend.runner ! LlamaBackendRunner.Command.Stop

              val updatedState = state.copy(
                drainingBackends = state.drainingBackends - backendId,
                allocatedPorts = state.allocatedPorts - backend.port
              )

              state.pendingStarts.headOption match {
                case Some(pending) =>
                  log.info(
                    "Processing pending start for backend {} after drain",
                    pending.backendConfig.id
                  )
                  pending.backendConfig.upstream_url match {
                    case Some(upstream_url) =>
                      pending.backendConfig.command match {
                        case Some(command) =>
                          startBackendWithUpstreamUrl(
                            context,
                            updatedState.copy(pendingStarts =
                              updatedState.pendingStarts.tail
                            ),
                            pending.backendConfig,
                            command,
                            upstream_url,
                            pending.requiredMemory,
                            pending.replyTo
                          )
                        case None =>
                          log.error(
                            "Pending backend {} missing command",
                            pending.backendConfig.id
                          )
                          pending.replyTo ! BackendResponse.Failed(
                            "Backend missing command"
                          )
                          active(
                            updatedState.copy(pendingStarts =
                              updatedState.pendingStarts.tail
                            )
                          )
                      }
                    case None =>
                      startBackend(
                        context,
                        updatedState.copy(pendingStarts =
                          updatedState.pendingStarts.tail
                        ),
                        pending.backendConfig,
                        pending.requiredMemory,
                        pending.replyTo
                      )
                  }
                case None =>
                  active(updatedState)
              }

            case None =>
              log.warn(
                "Received BackendDrained for unknown backend {}",
                backendId
              )
              Behaviors.same
          }

        case Command.AcquireHold(backendId, ttlSeconds, replyTo) =>
          state.config.backends.find(_.id == backendId) match {
            case None =>
              log.warn("Cannot acquire hold on unknown backend {}", backendId)
              replyTo ! HoldResponse.NotFound(backendId)
              Behaviors.same

            case Some(backendConfig) =>
              val currentTime = System.currentTimeMillis() / 1000
              val expiresAt = currentTime + ttlSeconds

              state.runningBackends.get(backendId) match {
                case Some(backend) =>
                  val endpoint = backendConfig.upstream_url.getOrElse(
                    s"http://127.0.0.1:${backend.port}"
                  )

                  log.info(
                    "Backend {} already running, acquiring hold (expires at {})",
                    backendId,
                    expiresAt
                  )

                  val hold = BackendHold(backendId, currentTime, expiresAt)
                  replyTo ! HoldResponse.Acquired(
                    backendId = backendId,
                    backendType = backendConfig.`type`,
                    status = "running",
                    port = backend.port,
                    endpoint = endpoint,
                    acquiredAt = currentTime,
                    expiresAt = expiresAt
                  )

                  active(
                    state.copy(
                      heldBackends = state.heldBackends + (backendId -> hold)
                    )
                  )

                case None =>
                  if (
                    backendConfig.`type` == "local" || backendConfig.`type` == "local-stub"
                  ) {
                    backendConfig.resources match {
                      case Some(resources) =>
                        MemoryParser.parseMemoryString(resources.memory) match {
                          case Right(requiredMemory) =>
                            val usedMemory = calculateUsedMemory(state)
                            val availableMemory = state.totalMemory - usedMemory

                            if (availableMemory >= requiredMemory) {
                              log.info(
                                "Acquiring hold and starting backend {}",
                                backendId
                              )
                              val hold =
                                BackendHold(backendId, currentTime, expiresAt)
                              val probe = context.spawn(
                                HoldAcquireResponder.behavior(
                                  backendId,
                                  backendConfig,
                                  replyTo,
                                  currentTime,
                                  expiresAt
                                ),
                                s"hold-acquire-$backendId-${java.util.UUID.randomUUID().toString.take(8)}"
                              )
                              handleLocalBackendRequest(
                                state.copy(
                                  heldBackends =
                                    state.heldBackends + (backendId -> hold)
                                ),
                                backendConfig,
                                probe
                              )
                            } else {
                              log.warn(
                                "Insufficient memory to acquire hold on backend {} (need {} MB, have {} MB)",
                                backendId,
                                requiredMemory / (1024 * 1024),
                                availableMemory / (1024 * 1024)
                              )
                              replyTo ! HoldResponse.InsufficientMemory(
                                backendId,
                                requiredMemory
                              )
                              Behaviors.same
                            }
                          case Left(error) =>
                            log.error(
                              "Failed to parse memory for backend {}: {}",
                              backendId,
                              error
                            )
                            replyTo ! HoldResponse.Failed(
                              backendId,
                              s"Invalid memory configuration: $error"
                            )
                            Behaviors.same
                        }
                      case None =>
                        log.error(
                          "Backend {} missing resources configuration",
                          backendId
                        )
                        replyTo ! HoldResponse.Failed(
                          backendId,
                          "Backend missing resources configuration"
                        )
                        Behaviors.same
                    }
                  } else {
                    log.warn(
                      "Cannot acquire hold on non-local backend {}",
                      backendId
                    )
                    replyTo ! HoldResponse.Failed(
                      backendId,
                      "Cannot hold remote backends"
                    )
                    Behaviors.same
                  }
              }
          }

        case Command.ReleaseHold(backendId, replyTo) =>
          val currentTime = System.currentTimeMillis() / 1000

          state.heldBackends.get(backendId) match {
            case Some(_) =>
              log.info("Releasing hold on backend {}", backendId)
              replyTo ! HoldResponse.Released(backendId, currentTime)
              active(state.copy(heldBackends = state.heldBackends - backendId))

            case None =>
              log.info(
                "Release hold on non-held backend {} (idempotent)",
                backendId
              )
              replyTo ! HoldResponse.Released(backendId, currentTime)
              Behaviors.same
          }

        case Command.CleanExpiredHolds =>
          val currentTime = System.currentTimeMillis() / 1000
          val expiredHolds =
            state.heldBackends.filter(_._2.expiresAt <= currentTime)

          if (expiredHolds.nonEmpty) {
            log.info(
              "Cleaning {} expired holds: {}",
              expiredHolds.size,
              expiredHolds.keys.mkString(", ")
            )
            active(state.copy(heldBackends = state.heldBackends -- expiredHolds.keys))
          } else {
            Behaviors.same
          }

        case Command.GetStatus(replyTo) =>
          val usedMemory = calculateUsedMemory(state)
          val backends = state.config.backends.map { backendConfig =>
            val id = backendConfig.id
            val hold = state.heldBackends.get(id)

            state.runningBackends.get(id) match {
              case Some(rb) =>
                BackendStatus(
                  id = id,
                  backend_type = backendConfig.`type`,
                  state = "running",
                  port = Some(rb.port),
                  memory_bytes = Some(rb.memoryUsed),
                  last_access_time = Some(rb.lastAccessTime),
                  held = hold.isDefined,
                  hold_expires_at = hold.map(_.expiresAt)
                )
              case None =>
                state.startingBackends.get(id) match {
                  case Some(sb) =>
                    BackendStatus(
                      id = id,
                      backend_type = backendConfig.`type`,
                      state = "starting",
                      port = if (sb.port != 0) Some(sb.port) else None,
                      memory_bytes = Some(sb.memoryReserved),
                      held = hold.isDefined,
                      hold_expires_at = hold.map(_.expiresAt)
                    )
                  case None =>
                    state.drainingBackends.get(id) match {
                      case Some(db) =>
                        BackendStatus(
                          id = id,
                          backend_type = backendConfig.`type`,
                          state = "draining",
                          port = Some(db.port),
                          memory_bytes = Some(db.memoryUsed),
                          held = hold.isDefined,
                          hold_expires_at = hold.map(_.expiresAt)
                        )
                      case None =>
                        BackendStatus(
                          id = id,
                          backend_type = backendConfig.`type`,
                          state = "stopped",
                          held = hold.isDefined,
                          hold_expires_at = hold.map(_.expiresAt)
                        )
                    }
                }
            }
          }

          replyTo ! StatusResponse(
            total_memory_bytes = state.totalMemory,
            used_memory_bytes = usedMemory,
            available_memory_bytes = state.totalMemory - usedMemory,
            backends = backends,
            pending_starts = state.pendingStarts.size
          )
          Behaviors.same

        case _ =>
          log.warn("Unexpected message in active state")
          Behaviors.same
      }

    def handleLocalBackendRequest(
        state: SupervisorState,
        backendConfig: BackendConfig,
        replyTo: ActorRef[BackendResponse]
    ): Behavior[Command] = {
      val backendId = backendConfig.id

      if (state.startingBackends.contains(backendId)) {
        log.info("Backend {} is already starting", backendId)
        replyTo ! BackendResponse.Starting
        return Behaviors.same
      }

      backendConfig.upstream_url match {
        case Some(upstream_url) =>
          backendConfig.command match {
            case Some(command) =>
              log.info(
                "Backend {} spawning process and using upstream URL: {}",
                backendId,
                upstream_url
              )

              backendConfig.resources match {
                case Some(resources) =>
                  MemoryParser.parseMemoryString(resources.memory) match {
                    case Right(requiredMemory) =>
                      val usedMemory = calculateUsedMemory(state)
                      val availableMemory = state.totalMemory - usedMemory

                      if (availableMemory >= requiredMemory) {
                        startBackendWithUpstreamUrl(
                          context,
                          state,
                          backendConfig,
                          command,
                          upstream_url,
                          requiredMemory,
                          replyTo
                        )
                      } else {
                        evictAndStartWithUpstreamUrl(
                          context,
                          state,
                          backendConfig,
                          command,
                          upstream_url,
                          requiredMemory,
                          replyTo
                        )
                      }
                    case Left(error) =>
                      log.error(
                        "Failed to parse memory for backend {}: {}",
                        backendId,
                        error
                      )
                      replyTo ! BackendResponse.Failed(
                        s"Invalid memory configuration: $error"
                      )
                      Behaviors.same
                  }
                case None =>
                  startBackendWithUpstreamUrl(
                    context,
                    state,
                    backendConfig,
                    command,
                    upstream_url,
                    0L,
                    replyTo
                  )
              }

            case None =>
              log.error("Backend {} missing command", backendId)
              replyTo ! BackendResponse.Failed("Backend missing command")
              Behaviors.same
          }

        case None =>
          backendConfig.resources match {
            case Some(resources) =>
              MemoryParser.parseMemoryString(resources.memory) match {
                case Right(requiredMemory) =>
                  val usedMemory = calculateUsedMemory(state)
                  val availableMemory = state.totalMemory - usedMemory

                  log.info(
                    "Backend {} requires {} MB, available {} MB",
                    backendId,
                    requiredMemory / (1024 * 1024),
                    availableMemory / (1024 * 1024)
                  )

                  if (availableMemory >= requiredMemory) {
                    startBackend(
                      context,
                      state,
                      backendConfig,
                      requiredMemory,
                      replyTo
                    )
                  } else {
                    evictAndStart(
                      context,
                      state,
                      backendConfig,
                      requiredMemory,
                      replyTo
                    )
                  }

                case Left(error) =>
                  log.error(
                    "Failed to parse memory for backend {}: {}",
                    backendId,
                    error
                  )
                  replyTo ! BackendResponse.Failed(
                    s"Invalid memory configuration: $error"
                  )
                  Behaviors.same
              }

            case None =>
              log.error("Backend {} missing resources configuration", backendId)
              replyTo ! BackendResponse.Failed(
                "Backend missing resources configuration"
              )
              Behaviors.same
          }
      }
    }

    def startBackend(
        context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
        state: SupervisorState,
        backendConfig: BackendConfig,
        requiredMemory: Long,
        replyTo: ActorRef[BackendResponse]
    ): Behavior[Command] =
      backendConfig.command match {
        case Some(command) =>
          PortAllocator.allocatePort(state.allocatedPorts) match {
            case Some(port) =>
              log.info(
                "Allocating port {} for backend {}",
                port,
                backendConfig.id
              )

              val uniqueId = java.util.UUID.randomUUID().toString.take(8)
              val runner = context.spawn(
                Behaviors
                  .supervise(LlamaBackendRunner.behavior)
                  .onFailure[Exception](
                    SupervisorStrategy.restart
                      .withLimit(maxNrOfRetries = 3, withinTimeRange = 1.minute)
                  ),
                s"backend-runner-${backendConfig.id}-$uniqueId"
              )

              runner ! LlamaBackendRunner.Command.Start(
                backendConfig.id,
                command,
                port,
                context.self
              )

              val startingBackend = StartingBackend(
                memoryReserved = requiredMemory,
                replyTo = replyTo,
                runner = runner,
                port = port
              )

              replyTo ! BackendResponse.Starting

              active(
                state.copy(
                  startingBackends =
                    state.startingBackends + (backendConfig.id -> startingBackend),
                  allocatedPorts = state.allocatedPorts + port
                )
              )

            case None =>
              log.error("No available ports for backend {}", backendConfig.id)
              replyTo ! BackendResponse.Failed("No available ports")
              Behaviors.same
          }

        case None =>
          log.error("Backend {} missing command", backendConfig.id)
          replyTo ! BackendResponse.Failed("Backend missing command")
          Behaviors.same
      }

    def evictAndStart(
        context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
        state: SupervisorState,
        backendConfig: BackendConfig,
        requiredMemory: Long,
        replyTo: ActorRef[BackendResponse]
    ): Behavior[Command] = {
      val (runningToEvict, startingToEvict) =
        evictBackendsForMemory(state, requiredMemory)

      if (runningToEvict.isEmpty && startingToEvict.isEmpty) {
        log.error(
          "Cannot start backend {}: insufficient memory even after evicting all backends",
          backendConfig.id
        )
        replyTo ! BackendResponse.Failed("Insufficient memory")
        Behaviors.same
      } else {
        log.info(
          "Evicting {} running and {} starting backends to free memory for {}: running={}, starting={}",
          runningToEvict.size,
          startingToEvict.size,
          backendConfig.id,
          runningToEvict.mkString(", "),
          startingToEvict.mkString(", ")
        )

        val drainingBackendsToAdd = runningToEvict.flatMap { backendId =>
          state.runningBackends.get(backendId).map { backend =>
            backendId -> DrainingBackend(
              port = backend.port,
              runner = backend.runner,
              memoryUsed = backend.memoryUsed,
              drainingStartedAt = System.currentTimeMillis()
            )
          }
        }.toMap

        state.dispatcherRef.foreach { dispatcher =>
          runningToEvict.foreach { backendId =>
            dispatcher ! radisson.actors.completion.CompletionRequestDispatcher.Command
              .BeginDraining(backendId)
          }
        }

        state.embeddingDispatcherRef.foreach { dispatcher =>
          runningToEvict.foreach { backendId =>
            dispatcher ! radisson.actors.embedding.EmbeddingRequestDispatcher.Command
              .BeginDraining(backendId)
          }
        }

        val updatedStartingBackends = state.startingBackends.map {
          case (id, backend) if startingToEvict.contains(id) =>
            log.info(
              "Marking starting backend {} for eviction after startup",
              id
            )
            id -> backend.copy(pendingEviction = true)
          case other => other
        }

        val pendingStart = PendingStart(
          backendConfig = backendConfig,
          requiredMemory = requiredMemory,
          replyTo = replyTo
        )

        active(
          state.copy(
            runningBackends = state.runningBackends -- runningToEvict,
            drainingBackends = state.drainingBackends ++ drainingBackendsToAdd,
            startingBackends = updatedStartingBackends,
            pendingStarts = state.pendingStarts :+ pendingStart
          )
        )
      }
    }

    def startBackendWithUpstreamUrl(
        context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
        state: SupervisorState,
        backendConfig: BackendConfig,
        command: String,
        upstream_url: String,
        requiredMemory: Long,
        replyTo: ActorRef[BackendResponse]
    ): Behavior[Command] = {
      val uniqueId = java.util.UUID.randomUUID().toString.take(8)
      val runner = context.spawn(
        Behaviors
          .supervise(LlamaBackendRunner.behavior)
          .onFailure[Exception](
            SupervisorStrategy.restart
              .withLimit(maxNrOfRetries = 3, withinTimeRange = 1.minute)
          ),
        s"backend-runner-${backendConfig.id}-$uniqueId"
      )

      runner ! LlamaBackendRunner.Command.Start(
        backendConfig.id,
        command,
        0,
        context.self,
        Some(upstream_url)
      )

      val startingBackend = StartingBackend(
        memoryReserved = requiredMemory,
        replyTo = replyTo,
        runner = runner,
        port = 0
      )

      replyTo ! BackendResponse.Starting

      active(
        state.copy(
          startingBackends =
            state.startingBackends + (backendConfig.id -> startingBackend)
        )
      )
    }

    def evictAndStartWithUpstreamUrl(
        context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
        state: SupervisorState,
        backendConfig: BackendConfig,
        command: String,
        upstream_url: String,
        requiredMemory: Long,
        replyTo: ActorRef[BackendResponse]
    ): Behavior[Command] = {
      val (runningToEvict, startingToEvict) =
        evictBackendsForMemory(state, requiredMemory)

      if (runningToEvict.isEmpty && startingToEvict.isEmpty) {
        log.error(
          "Cannot start backend {}: insufficient memory even after evicting all backends",
          backendConfig.id
        )
        replyTo ! BackendResponse.Failed("Insufficient memory")
        Behaviors.same
      } else {
        log.info(
          "Evicting {} running and {} starting backends to free memory for {}: running={}, starting={}",
          runningToEvict.size,
          startingToEvict.size,
          backendConfig.id,
          runningToEvict.mkString(", "),
          startingToEvict.mkString(", ")
        )

        val drainingBackendsToAdd = runningToEvict.flatMap { backendId =>
          state.runningBackends.get(backendId).map { backend =>
            backendId -> DrainingBackend(
              port = backend.port,
              runner = backend.runner,
              memoryUsed = backend.memoryUsed,
              drainingStartedAt = System.currentTimeMillis()
            )
          }
        }.toMap

        state.dispatcherRef.foreach { dispatcher =>
          runningToEvict.foreach { backendId =>
            dispatcher ! radisson.actors.completion.CompletionRequestDispatcher.Command
              .BeginDraining(backendId)
          }
        }

        state.embeddingDispatcherRef.foreach { dispatcher =>
          runningToEvict.foreach { backendId =>
            dispatcher ! radisson.actors.embedding.EmbeddingRequestDispatcher.Command
              .BeginDraining(backendId)
          }
        }

        val updatedStartingBackends = state.startingBackends.map {
          case (id, backend) if startingToEvict.contains(id) =>
            log.info(
              "Marking starting backend {} for eviction after startup",
              id
            )
            id -> backend.copy(pendingEviction = true)
          case other => other
        }

        val pendingStart = PendingStart(
          backendConfig = backendConfig,
          requiredMemory = requiredMemory,
          replyTo = replyTo
        )

        active(
          state.copy(
            runningBackends = state.runningBackends -- runningToEvict,
            drainingBackends = state.drainingBackends ++ drainingBackendsToAdd,
            startingBackends = updatedStartingBackends,
            pendingStarts = state.pendingStarts :+ pendingStart
          )
        )
      }
    }

      uninitialized()
    }
  }

  private def calculateUsedMemory(state: SupervisorState): Long = {
    val runningMemory = state.runningBackends.values.map(_.memoryUsed).sum
    val startingMemory = state.startingBackends.values.map(_.memoryReserved).sum
    runningMemory + startingMemory
  }

  private def evictBackendsForMemory(
      state: SupervisorState,
      requiredMemory: Long
  ): (List[String], List[String]) = {
    val usedMemory = calculateUsedMemory(state)
    val availableMemory = state.totalMemory - usedMemory

    if (availableMemory >= requiredMemory) {
      return (List.empty, List.empty)
    }

    val currentTime = System.currentTimeMillis() / 1000
    val activeHeldBackends = state.heldBackends.filter { case (_, hold) =>
      hold.expiresAt > currentTime
    }.keySet

    var freedMemory = availableMemory
    val runningToEvict = scala.collection.mutable.ListBuffer[String]()
    val startingToEvict = scala.collection.mutable.ListBuffer[String]()

    val sortedRunning = state.runningBackends.toList
      .filter { case (id, _) => !activeHeldBackends.contains(id) }
      .sortBy(_._2.lastAccessTime)
    for ((id, backend) <- sortedRunning if freedMemory < requiredMemory) {
      runningToEvict += id
      freedMemory += backend.memoryUsed
    }

    if (freedMemory < requiredMemory) {
      val sortedStarting = state.startingBackends.toList
        .filter { case (id, _) => !activeHeldBackends.contains(id) }
        .sortBy(_._1)
      for ((id, backend) <- sortedStarting if freedMemory < requiredMemory) {
        startingToEvict += id
        freedMemory += backend.memoryReserved
      }
    }

    (runningToEvict.toList, startingToEvict.toList)
  }

  private def findPortForBackend(
      state: SupervisorState,
      backendId: String
  ): Option[Int] =
    state.runningBackends.get(backendId).map(_.port)

  object HoldAcquireResponder {
    def behavior(
        backendId: String,
        backendConfig: BackendConfig,
        replyTo: ActorRef[HoldResponse],
        acquiredAt: Long,
        expiresAt: Long
    ): Behavior[BackendResponse] = Behaviors.receiveMessage {
      case BackendResponse.Available(endpoint, port) =>
        replyTo ! HoldResponse.Acquired(
          backendId = backendId,
          backendType = backendConfig.`type`,
          status = "running",
          port = port,
          endpoint = endpoint,
          acquiredAt = acquiredAt,
          expiresAt = expiresAt
        )
        Behaviors.stopped

      case BackendResponse.Starting =>
        Behaviors.same

      case BackendResponse.Failed(reason) =>
        replyTo ! HoldResponse.Failed(backendId, reason)
        Behaviors.stopped
    }
  }
}
