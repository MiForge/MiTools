package com.offici5l.mitools.miassistant

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ApiException(message: String) : Exception(message)

/** One package entry that carries a signed "Validate" token (see extractValidate). */
class ValidateEntry(val md5: String?, val validate: String, val erase: Any?)

/**
 * Talks to Xiaomi's OTA server the same way the PC "Mi Assistant" does:
 * an AES-CBC encrypted request describing the phone + the md5 of the ROM, and an
 * RSA-signed "Validate" token in the answer that the recovery checks before flashing.
 */
object MiAssistantApi {

    private const val URL = "http://update.miui.com/updates/miotaV3.php"
    private const val USER_AGENT = "MiTunes_UserAgent_v3.0"
    private val KEY = "miuiotavalided11".toByteArray(Charsets.US_ASCII)
    private val IV = "0102030405060708".toByteArray(Charsets.US_ASCII)

    private const val PUBLIC_KEY_B64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAs3X/eMU56OEc5buvfnXeD+9X4xKsImY7Q3Y+FY/xcdRIIvypKPjqxgqV548+Vf/H2VHeUXsMgiaeAqbmNAwPBU0He0p0YlUEOZ9Bkn6dKniuEe9G7PeL3tx6qjnf+Y5actf7IwJBTGJefa7wt+OZ4pNCBfgPHkTW+3VGlioP8rHbnLyZ98BdNxrvcLqJHCTblwomhVR64syqP1/eVOqEBKeHT+IkTXFZAzEVGha0FyKgBwcET/851zW93FiiC1HYpubLSiK1crbtDVK2ho4zNWzHCPfAfKQQn1VqXthiwxTZ25i4FYbuySwZ7xfXhqJwcLKuxN9uRCHBG8KV9V5T7wIDAQAB"

    private val publicKey: RSAPublicKey by lazy {
        KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY_B64))) as RSAPublicKey
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // JSON text exactly like Python's json.dumps (", " / ": " separators, ASCII-only).
    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 || c.code > 0x7e -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private fun aes(mode: Int, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(mode, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV))
        return cipher.doFinal(data)
    }

    /** Asks the server about a package. [md5] = md5 of the local zip, or "" to list candidates. */
    fun validateRequest(info: Map<String, String>, md5: String?): JSONObject {
        val zone = info["romzone"]?.trim()?.toIntOrNull()
            ?: throw ApiException("The device returned an invalid ROM zone.")

        val payload = "{" +
            "\"d\": ${jsonString(info["device"] ?: "")}, " +
            "\"v\": ${jsonString(info["version"] ?: "")}, " +
            "\"c\": ${jsonString(info["codebase"] ?: "")}, " +
            "\"b\": ${jsonString(info["branch"] ?: "")}, " +
            "\"sn\": ${jsonString(info["sn"] ?: "")}, " +
            "\"l\": \"en-US\", \"f\": \"1\", " +
            "\"options\": {\"zone\": $zone}, " +
            "\"pkg\": ${if (md5 == null) "null" else jsonString(md5)}" +
            "}"

        val enc = aes(Cipher.ENCRYPT_MODE, payload.toByteArray(Charsets.UTF_8))
        // Same as Python's urllib.parse.quote(b64): '/' stays, '+' and '=' are escaped.
        val q = URLEncoder.encode(Base64.getEncoder().encodeToString(enc), "UTF-8").replace("%2F", "/")

        val body = "q=$q&t=&s=1".toByteArray(Charsets.US_ASCII)
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = Request.Builder()
            .url(URL)
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException("Server error ${resp.code} from update.miui.com.")
                val text = resp.body?.string()?.trim().orEmpty()
                val dec = aes(Cipher.DECRYPT_MODE, Base64.getMimeDecoder().decode(text))
                return JSONObject(String(dec, Charsets.UTF_8))
            }
        } catch (e: IOException) {
            throw ApiException("Network error while contacting update.miui.com: ${e.message}")
        } catch (e: JSONException) {
            throw ApiException("Unexpected response from update.miui.com.")
        } catch (e: IllegalArgumentException) {
            throw ApiException("Unexpected response from update.miui.com.")
        } catch (e: java.security.GeneralSecurityException) {
            throw ApiException("Unexpected response from update.miui.com.")
        }
    }

    /** "PkgRom" object of a server answer (empty when absent). */
    fun pkgRom(response: JSONObject): JSONObject = response.optJSONObject("PkgRom") ?: JSONObject()

    /** Verifies the server signature on a Validate token and returns the signed JSON. */
    fun validateDecrypt(validate: String): JSONObject {
        val key = publicKey
        val encrypted = Base64.getMimeDecoder().decode(validate)
        val m = BigInteger(1, encrypted).modPow(key.publicExponent, key.modulus)
        val keySize = (key.modulus.bitLength() + 7) / 8

        val raw = m.toByteArray()
        val padded = ByteArray(keySize)
        if (raw.size <= keySize) {
            System.arraycopy(raw, 0, padded, keySize - raw.size, raw.size)
        } else {
            System.arraycopy(raw, raw.size - keySize, padded, 0, keySize)
        }

        if (padded[0].toInt() != 0x00 || padded[1].toInt() != 0x01) {
            throw ApiException("Invalid padding (not PKCS#1 Type 1).")
        }
        var sep = -1
        for (i in 2 until padded.size) {
            if (padded[i].toInt() == 0) {
                sep = i
                break
            }
        }
        if (sep == -1) throw ApiException("Padding separator (0x00) not found.")
        val text = String(padded, sep + 1, padded.size - sep - 1, Charsets.UTF_8).replace("}{", ",")
        try {
            return JSONObject(text)
        } catch (e: JSONException) {
            throw ApiException("The server signature could not be read.")
        }
    }

    /** SHA1 recorded in a Validate token, or null if the token can't be verified. */
    fun signedSha1(validate: String): String? = try {
        validateDecrypt(validate).optString("SHA1").takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    /** Collects every nested object that has a "Validate" token, keyed by its parent key. */
    fun extractValidate(root: JSONObject): LinkedHashMap<String, ValidateEntry> {
        val result = LinkedHashMap<String, ValidateEntry>()
        val seen = HashSet<String?>()
        walk(root, null, result, seen)
        return result
    }

    private fun walk(obj: Any?, parent: String?, result: LinkedHashMap<String, ValidateEntry>, seen: HashSet<String?>) {
        when (obj) {
            is JSONObject -> {
                if (obj.has("Validate") && !parent.isNullOrEmpty()) {
                    val md5 = obj.opt("md5")?.takeIf { it != JSONObject.NULL }?.toString()
                    if (seen.add(md5)) {
                        result[parent] = ValidateEntry(md5, obj.optString("Validate"), obj.opt("Erase"))
                    }
                }
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    walk(obj.opt(k), k, result, seen)
                }
            }
            is JSONArray -> for (i in 0 until obj.length()) walk(obj.opt(i), parent, result, seen)
        }
    }

    fun eraseText(value: Any?): String =
        if (value == null || value == JSONObject.NULL) "None" else value.toString()
}
