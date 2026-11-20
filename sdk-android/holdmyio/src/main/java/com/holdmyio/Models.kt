package com.holdmyio

import org.json.JSONArray
import org.json.JSONObject

/** Purchase attached to an event. `amount` is the price the buyer paid, `currency` an ISO 4217 code. */
internal data class Revenue(val amount: Double, val currency: String, val productId: String)

/** A single event, as sent to `/v1/events` (same fields as the Swift SDK). */
internal data class Event(
    val id: String,
    val name: String,
    /** Milliseconds since 1970-01-01 UTC. */
    val ts: Long,
    val sessionId: String,
    val screen: String,
    val props: Map<String, Any>,
    val revenue: Revenue? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("ts", ts)
        put("session_id", sessionId)
        put("screen", screen)
        put("props", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
        revenue?.let {
            put("revenue", JSONObject().apply {
                put("amount", it.amount)
                put("currency", it.currency)
                put("product_id", it.productId)
            })
        }
    }

    companion object {
        fun fromJson(json: JSONObject): Event {
            val props = mutableMapOf<String, Any>()
            json.optJSONObject("props")?.let { p -> p.keys().forEach { k -> props[k] = p.get(k) } }
            val revenue = json.optJSONObject("revenue")?.let {
                Revenue(it.getDouble("amount"), it.getString("currency"), it.optString("product_id"))
            }
            return Event(
                id = json.getString("id"),
                name = json.getString("name"),
                ts = json.getLong("ts"),
                sessionId = json.optString("session_id"),
                screen = json.optString("screen"),
                props = props,
                revenue = revenue,
            )
        }
    }
}

/** Device and app context, sent with every batch. */
internal data class DeviceContext(
    val appVersion: String,
    val build: String,
    val platform: String,
    val osVersion: String,
    val deviceModel: String,
    val locale: String,
)

/** What persists on disk between launches. */
internal data class PersistedState(
    val installId: String,
    var enabled: Boolean,
    val queue: MutableList<Event>,
) {
    fun toJson(): String = JSONObject().apply {
        put("installId", installId)
        put("enabled", enabled)
        put("queue", JSONArray().apply { queue.forEach { put(it.toJson()) } })
    }.toString()

    companion object {
        fun fromJson(text: String): PersistedState {
            val json = JSONObject(text)
            val queue = mutableListOf<Event>()
            json.optJSONArray("queue")?.let { arr ->
                for (i in 0 until arr.length()) runCatching { queue += Event.fromJson(arr.getJSONObject(i)) }
            }
            return PersistedState(json.getString("installId"), json.optBoolean("enabled", true), queue)
        }
    }
}

/** Allowed property values: string, number, bool. Anything else is dropped. */
internal fun cleanProps(props: Map<String, Any?>): Map<String, Any> =
    props.mapNotNull { (k, v) ->
        when (v) {
            is String, is Boolean, is Int, is Long, is Double, is Float -> k to v
            is Number -> k to v.toDouble()
            else -> null
        }
    }.toMap()
