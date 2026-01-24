package radisson.actors.backend

import scala.concurrent.duration._

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
  }

  enum BackendResponse {
    case Available(endpoint: String, port: Int)
    case Starting
    case Failed(reason: String)
  }

  case class RunningBackend(
      port: Int,
      runner: ActorRef[LlamaBackendRunner.Command],
      memoryUsed: Long,
      lastAccessTime: Long
  )

  case class StartingBackend(
      memoryReserved: Long,
      replyTo: ActorRef[BackendResponse]
  )

  case class SupervisorState(
      config: AppConfig,
      totalMemory: Long,
      runningBackends: Map[String, RunningBackend],
      startingBackends: Map[String, StartingBackend],
      allocatedPorts: Set[Int]
  )

  def behavior: Behavior[Command] = Behaviors.setup { context =>
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
          allocatedPorts = Set.empty
        )

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
              log.info(
                "Backend {} already running on port {}",
                backendId,
                backend.port
              )
              val updatedBackend =
                backend.copy(lastAccessTime = System.currentTimeMillis())
              replyTo ! BackendResponse.Available(
                s"http://127.0.0.1:${backend.port}",
                backend.port
              )
              active(
                state.copy(
                  runningBackends =
                    state.runningBackends.updated(backendId, updatedBackend)
                )
              )

            case None =>
              state.config.backends.find(_.id == backendId) match {
                case Some(backendConfig) if backendConfig.`type` == "local" =>
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
              log.info(
                "Backend {} successfully started on port {}",
                backendId,
                port
              )

              val runningBackend = RunningBackend(
                port = port,
                runner = runner,
                memoryUsed = starting.memoryReserved,
                lastAccessTime = System.currentTimeMillis()
              )

              starting.replyTo ! BackendResponse.Available(
                s"http://127.0.0.1:$port",
                port
              )

              active(
                state.copy(
                  runningBackends =
                    state.runningBackends + (backendId -> runningBackend),
                  startingBackends = state.startingBackends - backendId
                )
              )

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
              starting.replyTo ! BackendResponse.Failed(reason)

              active(
                state.copy(
                  startingBackends = state.startingBackends - backendId,
                  allocatedPorts =
                    state.allocatedPorts - findPortForBackend(state, backendId)
                      .getOrElse(0)
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

              val runner = context.spawn(
                Behaviors
                  .supervise(LlamaBackendRunner.behavior)
                  .onFailure[Exception](
                    SupervisorStrategy.restart
                      .withLimit(maxNrOfRetries = 3, withinTimeRange = 1.minute)
                  ),
                s"backend-runner-${backendConfig.id}"
              )

              runner ! LlamaBackendRunner.Command.Start(
                backendConfig.id,
                command,
                port,
                context.self
              )

              val startingBackend = StartingBackend(
                memoryReserved = requiredMemory,
                replyTo = replyTo
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
      val toEvict = evictLRUBackends(state, requiredMemory)

      if (toEvict.isEmpty) {
        log.error(
          "Cannot start backend {}: insufficient memory and no backends to evict",
          backendConfig.id
        )
        replyTo ! BackendResponse.Failed("Insufficient memory")
        Behaviors.same
      } else {
        log.info(
          "Evicting {} backends to free memory for {}: {}",
          toEvict.size,
          backendConfig.id,
          toEvict.mkString(", ")
        )

        toEvict.foreach { backendId =>
          state.runningBackends.get(backendId).foreach { backend =>
            backend.runner ! LlamaBackendRunner.Command.Stop
          }
        }

        val updatedState = state.copy(
          runningBackends = state.runningBackends -- toEvict,
          allocatedPorts = state.allocatedPorts -- toEvict.flatMap { id =>
            state.runningBackends.get(id).map(_.port)
          }
        )

        startBackend(
          context,
          updatedState,
          backendConfig,
          requiredMemory,
          replyTo
        )
      }
    }

    uninitialized()
  }

  private def calculateUsedMemory(state: SupervisorState): Long = {
    val runningMemory = state.runningBackends.values.map(_.memoryUsed).sum
    val startingMemory = state.startingBackends.values.map(_.memoryReserved).sum
    runningMemory + startingMemory
  }

  private def evictLRUBackends(
      state: SupervisorState,
      requiredMemory: Long
  ): List[String] = {
    val sortedByLRU = state.runningBackends.toList
      .sortBy(_._2.lastAccessTime)

    var freedMemory = 0L
    val toEvict = scala.collection.mutable.ListBuffer[String]()

    for ((id, backend) <- sortedByLRU if freedMemory < requiredMemory) {
      toEvict += id
      freedMemory += backend.memoryUsed
    }

    toEvict.toList
  }

  private def findPortForBackend(
      state: SupervisorState,
      backendId: String
  ): Option[Int] =
    state.runningBackends.get(backendId).map(_.port)
}
