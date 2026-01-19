package org.onekash.icaldav.client

import org.onekash.icaldav.xml.MultiStatusParser
import java.io.File
import kotlin.system.measureTimeMillis

fun main() {
    val xmlFile = File("/tmp/caldav_query.xml")
    if (!xmlFile.exists()) {
        println("XML file not found. Run curl test first.")
        return
    }
    
    println("=== Parser Benchmark ===")
    println("File size: ${xmlFile.length()} bytes")
    
    val xml = xmlFile.readText()
    println("XML loaded: ${xml.length} chars")
    
    println("\nParsing with MultiStatusParser...")
    val parser = MultiStatusParser.INSTANCE
    
    val parseTime = measureTimeMillis {
        val result = parser.parse(xml)
        when (result) {
            is org.onekash.icaldav.model.DavResult.Success -> {
                println("Parse success!")
                println("Responses: ${result.value.responses.size}")
                println("With calendar-data: ${result.value.responses.count { it.calendarData != null }}")
            }
            else -> println("Parse failed: $result")
        }
    }
    println("Parse time: ${parseTime}ms")
}
