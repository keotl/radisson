package radisson.actors.http.api.models

import io.circe.Codec

case class AcquireHoldRequest(
    ttl_seconds: Option[Int] = None
) derives Codec.AsObject

case class AcquireHoldResponse(
    backend_id: String,
    backend_type: String,
    status: String,
    port: Int,
    endpoint: String,
    hold_acquired_at: Long,
    hold_expires_at: Long
) derives Codec.AsObject

case class ReleaseHoldResponse(
    backend_id: String,
    status: String,
    released_at: Long
) derives Codec.AsObject
