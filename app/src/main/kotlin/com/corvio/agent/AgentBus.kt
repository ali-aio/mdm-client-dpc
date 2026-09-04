package com.corvio.agent

import com.corvio.agent.net.Acker
import org.json.JSONObject

/**
 * Tiny process-wide wiring hub so the screen-capture service and accessibility service (which the
 * system instantiates independently) can reach the live transport / input sink owned by
 * [MdmService], without a bound-service dance.
 */
object AgentBus {
    /** Live transport sink; set by MdmService while connected. */
    @Volatile var acker: Acker? = null

    /** Input sink; set by the accessibility service once the user enables it. */
    @Volatile var input: InputSink? = null

    interface InputSink {
        fun handle(event: JSONObject)
    }
}
