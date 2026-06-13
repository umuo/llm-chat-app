package com.lacknb.agentchat.core.provider

interface ApiKeyStore {
    fun save(ref: String, apiKey: String)
    fun load(ref: String): String?
    fun delete(ref: String)
}
