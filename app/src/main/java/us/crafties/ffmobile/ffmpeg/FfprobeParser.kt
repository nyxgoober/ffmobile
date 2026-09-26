package us.crafties.ffmobile.ffmpeg

import org.json.JSONObject

data class StreamInfo(
    val index: Int,
    val codecType: String,
    val codecName: String,
    val width: Int?,
    val height: Int?,
    val sampleRate: String?,
    val channels: Int?,
    val bitRate: String?,
    val frameRate: String?,
    /** True for embedded cover art (ffprobe disposition attached_pic=1). Not a playable video track. */
    val isAttachedPic: Boolean = false,
)

data class MediaInfo(
    val filename: String,
    val formatName: String,
    val durationSeconds: Double,
    val sizeBytes: Long,
    val bitRate: String?,
    val streams: List<StreamInfo>,
)

object FfprobeParser {
    fun parse(json: String): MediaInfo? {
        return try {
            val root = JSONObject(json)
            val format = root.optJSONObject("format")
            val streamsArr = root.optJSONArray("streams")
            val streams = mutableListOf<StreamInfo>()
            if (streamsArr != null) {
                for (i in 0 until streamsArr.length()) {
                    val s = streamsArr.getJSONObject(i)
                    val rawFrameRate = s.optString("r_frame_rate", "")
                    streams.add(
                        StreamInfo(
                            index = s.optInt("index"),
                            codecType = s.optString("codec_type", "?"),
                            codecName = s.optString("codec_name", "?"),
                            width = if (s.has("width")) s.optInt("width") else null,
                            height = if (s.has("height")) s.optInt("height") else null,
                            sampleRate = if (s.has("sample_rate")) s.optString("sample_rate") else null,
                            channels = if (s.has("channels")) s.optInt("channels") else null,
                            bitRate = if (s.has("bit_rate")) s.optString("bit_rate") else null,
                            frameRate = simplifyFraction(rawFrameRate),
                            isAttachedPic = s.optJSONObject("disposition")?.optInt("attached_pic", 0) == 1,
                        )
                    )
                }
            }
            MediaInfo(
                filename = format?.optString("filename") ?: "",
                formatName = format?.optString("format_long_name") ?: format?.optString("format_name") ?: "unknown",
                durationSeconds = format?.optString("duration")?.toDoubleOrNull() ?: 0.0,
                sizeBytes = format?.optString("size")?.toLongOrNull() ?: 0L,
                bitRate = format?.optString("bit_rate"),
                streams = streams,
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun simplifyFraction(fraction: String): String? {
        val parts = fraction.split("/")
        if (parts.size != 2) return fraction.ifBlank { null }
        val num = parts[0].toDoubleOrNull() ?: return fraction
        val den = parts[1].toDoubleOrNull() ?: return fraction
        if (den == 0.0) return null
        return String.format("%.2f fps", num / den)
    }
}
