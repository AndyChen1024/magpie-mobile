package com.andychen.magpie.shared.usage

internal object UsageFixtures {
    const val summary = """{"calls":3,"errors":1,"input":120,"output":30,"cost":0.125,"unpriced":1,"future":{}}"""
    const val zero = """{"calls":0,"errors":0,"input":0,"output":0,"cost":0,"unpriced":0}"""
    const val restricted = """{"error":{"message":"synthetic","code":"gateway_key_held"}}"""
    const val row = """{"t":"2026-11-20T10:00:00.125+08:00","agentName":"Demo Agent","agent":"demo","model":"demo/model","status":0,"priced":false,"cost":8}"""

    fun page(offset: Int, total: Int, vararg rows: String) =
        """{"offset":$offset,"total":$total,"rows":[${rows.joinToString(",")}]}"""
}
