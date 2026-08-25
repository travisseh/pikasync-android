package com.travisse.pikasync.pipeline

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

/**
 * Persisted face-identity clusters — "who is in this library." Same JSON shape
 * and semantics as the iOS PeopleStore (Documents/people.json) so centroids
 * can sync across platforms later: id, name, role neutral|required|excluded,
 * centroid (512 floats, L2-normalized running mean), count, repCropPNG (base64).
 */
object PeopleStore {
    // Same-person cosines measured on this library run 0.38-0.65; different
    // adults < 0.05, worst adult-vs-baby 0.24. 0.32 splits those cleanly.
    const val MATCH_THRESHOLD = 0.32f

    data class PersonCluster(
        val id: String,
        var name: String,
        var role: String,          // "neutral" | "required" | "excluded"
        var centroid: FloatArray,
        var count: Int,
        var repCropPNG: ByteArray?,
    )

    private var cache: MutableList<PersonCluster>? = null

    private fun file(context: Context) = File(context.filesDir, "people.json")

    @Synchronized
    fun load(context: Context): List<PersonCluster> {
        cache?.let { return it }
        val out = mutableListOf<PersonCluster>()
        try {
            val arr = JSONArray(file(context).readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val cArr = o.getJSONArray("centroid")
                val centroid = FloatArray(cArr.length()) { cArr.getDouble(it).toFloat() }
                out += PersonCluster(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    role = o.optString("role", "neutral"),
                    centroid = centroid,
                    count = o.optInt("count", 1),
                    repCropPNG = o.optString("repCropPNG", "").takeIf { it.isNotEmpty() }
                        ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() },
                )
            }
        } catch (_: Exception) { /* first run: empty */ }
        cache = out
        return out
    }

    @Synchronized
    fun save(context: Context) {
        val clusters = cache ?: return
        val arr = JSONArray()
        for (c in clusters) {
            val cent = JSONArray().also { a -> c.centroid.forEach { a.put(it.toDouble()) } }
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("role", c.role)
                    .put("centroid", cent)
                    .put("count", c.count)
                    .put("repCropPNG", c.repCropPNG?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: "")
            )
        }
        file(context).writeText(arr.toString())
    }

    /** Assign one embedding to a cluster (creating one if new person). Returns cluster id. */
    @Synchronized
    fun assign(context: Context, embedding: FloatArray, cropPNG: ByteArray?): String {
        val clusters = load(context) as MutableList<PersonCluster>
        var bestIdx = -1
        var bestSim = 0f
        clusters.forEachIndexed { i, c ->
            val sim = FaceEmbedder.cosine(embedding, c.centroid)
            if (sim > bestSim) { bestSim = sim; bestIdx = i }
        }
        if (bestIdx >= 0 && bestSim >= MATCH_THRESHOLD) {
            val c = clusters[bestIdx]
            val n = c.count.toFloat()
            val merged = FloatArray(c.centroid.size) { (c.centroid[it] * n + embedding[it]) / (n + 1) }
            val norm = sqrt(merged.fold(0f) { acc, v -> acc + v * v })
            if (norm > 0f) for (i in merged.indices) merged[i] /= norm
            c.centroid = merged
            c.count += 1
            if (c.repCropPNG == null && cropPNG != null) c.repCropPNG = cropPNG
            return c.id
        }
        val fresh = PersonCluster(
            id = UUID.randomUUID().toString().uppercase(),
            name = "Person ${clusters.size + 1}",
            role = "neutral",
            centroid = embedding,
            count = 1,
            repCropPNG = cropPNG,
        )
        clusters.add(fresh)
        return fresh.id
    }

    @Synchronized
    fun update(context: Context, cluster: PersonCluster) {
        val clusters = load(context) as MutableList<PersonCluster>
        val i = clusters.indexOfFirst { it.id == cluster.id }
        if (i >= 0) { clusters[i] = cluster; save(context) }
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        val clusters = load(context) as MutableList<PersonCluster>
        clusters.removeAll { it.id == id }
        save(context)
    }

    @Synchronized
    fun clear(context: Context) {
        cache = mutableListOf()
        save(context)
    }

    fun requiredIds(context: Context): Set<String> =
        load(context).filter { it.role == "required" }.map { it.id }.toSet()

    fun excludedIds(context: Context): Set<String> =
        load(context).filter { it.role == "excluded" }.map { it.id }.toSet()

    fun nameOf(context: Context, id: String): String? =
        load(context).firstOrNull { it.id == id }?.name
}
