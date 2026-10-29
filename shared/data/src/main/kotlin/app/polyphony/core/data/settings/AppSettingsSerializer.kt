package app.polyphony.core.data.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.polyphony.core.domain.repository.AppSettings
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/** JSON on disk: human-readable, forward-compatible (unknown keys ignored, missing keys default). */
class AppSettingsSerializer(private val json: Json) : Serializer<AppSettings> {
    override val defaultValue: AppSettings = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings {
        val text = input.readBytes().decodeToString()
        if (text.isBlank()) return defaultValue
        return try {
            json.decodeFromString(AppSettings.serializer(), text)
        } catch (e: SerializationException) {
            throw CorruptionException("Cannot read settings", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Cannot read settings", e)
        }
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(json.encodeToString(AppSettings.serializer(), t).encodeToByteArray())
    }
}
