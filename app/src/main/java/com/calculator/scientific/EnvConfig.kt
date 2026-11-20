package com.calculator.scientific

import android.content.Context

/**
 * Central configuration helper that connects the secret API key from `.env`
 * (compiled into [BuildConfig.OPENAI_API_KEY]) to the rest of the app.
 */
object EnvConfig {

    private const val PREFS_NAME = "calc_ai_prefs"
    private const val KEY_OVERRIDE = "openai_api_key"
    private const val PLACEHOLDER_KEY = "sk-replace-with-your-openai-api-key"

    /**
     * Returns the OpenAI API key loaded from the root `.env` file
     * (or an in-app override if the user manually saved one via the settings dialog).
     */
    fun getOpenAiApiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedOverride = prefs.getString(KEY_OVERRIDE, "")?.trim().orEmpty()
        if (savedOverride.isNotEmpty() && savedOverride != PLACEHOLDER_KEY) {
            return savedOverride
        }

        val envKey = BuildConfig.OPENAI_API_KEY.trim()
        return if (envKey == PLACEHOLDER_KEY) "" else envKey
    }

    fun saveOverrideApiKey(context: Context, newKey: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_OVERRIDE, newKey.trim()).apply()
    }
}
