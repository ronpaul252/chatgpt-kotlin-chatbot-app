package com.chatgptlite.wanted.data.remote

import com.chatgptlite.wanted.constants.matchResultTurboString
import com.chatgptlite.wanted.data.api.OpenAIApi
import com.chatgptlite.wanted.models.TextCompletionsParam
import com.chatgptlite.wanted.models.toJson
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okio.IOException
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject


@Suppress("UNREACHABLE_CODE")
class OpenAIRepositoryImpl @Inject constructor(
    private val openAIApi: OpenAIApi,
): OpenAIRepository {
    override fun textCompletionsWithStream(params: TextCompletionsParam): Flow<String> = callbackFlow {
        withContext(Dispatchers.IO) {
            val response = openAIApi.textCompletionsWithStream(params.toJson()).execute()

            if (response.isSuccessful) {
                val input = response.body()?.byteStream()?.bufferedReader() ?: throw Exception()
                try {
                    while (true) {
                        val line =
                            withContext(Dispatchers.IO) {
                                input.readLine()
                            } ?: continue
                        if (line == "data: [DONE]") {
                            close()
                        } else if (line.startsWith("data:")) {
                            try {
                                // Handle & convert data -> emit to client
                                val value = lookupDataFromResponseTurbo(line)
                                trySend(value)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                } catch (e: IOException) {
                    throw Exception(e)
                } finally {
                    withContext(Dispatchers.IO) {
                        input.close()
                    }

                    awaitClose {
                        close()
                    }
                }
            } else {
                if (!response.isSuccessful) {
                    var jsonObject: JSONObject? = null
                    try {
                        jsonObject = JSONObject(response.errorBody()!!.string())
                    } catch (e: JSONException) {
                        e.printStackTrace()
                    }
                }
                trySend("Failure! Try again.")
                awaitClose {
                    close()
                }
            }
        }
        close()
    }

    private fun lookupDataFromResponseTurbo(jsonString: String): String {
        val splitsJsonString = jsonString.split("[{")

        val indexOfResult: Int = splitsJsonString.indexOfLast {
            it.contains(matchResultTurboString)
        }

        val textSplits = if (indexOfResult == -1) listOf() else splitsJsonString[indexOfResult].split(",")

        val indexOfText: Int = textSplits.indexOfLast {
            it.contains(matchResultTurboString)
        }

        if (indexOfText != -1) {
            try {
                val gson = Gson()
                val jsonObject = gson.fromJson("{${textSplits[indexOfText]}}", JsonObject::class.java)

                return jsonObject.getAsJsonObject("delta").get("content").asString
            } catch (e: java.lang.Exception) {
                println(e.localizedMessage)
            }
        }

        return ""
    }
}