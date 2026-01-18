package org.onekash.icaldav.client

import org.onekash.icaldav.xml.MultiStatusParser
import org.onekash.icaldav.model.DavResult
import org.onekash.icaldav.parser.ICalParser
import org.onekash.icaldav.model.ParseResult
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.system.measureTimeMillis

class ParserBenchmarkTest {
    
    @Test
    fun `benchmark full pipeline`() {
        val xmlFile = File("/tmp/caldav_query.xml")
        if (!xmlFile.exists()) {
            println("XML file not found")
            return
        }
        
        println("=== Full Pipeline Benchmark ===")
        println("File size: ${xmlFile.length()} bytes")
        
        val xml = xmlFile.readText()
        println("XML loaded: ${xml.length} chars")
        
        // Step 1: Parse XML
        println("\n--- Step 1: MultiStatusParser ---")
        val parser = MultiStatusParser.INSTANCE
        var responses: List<org.onekash.icaldav.model.DavResponse> = emptyList()
        
        val xmlParseTime = measureTimeMillis {
            val result = parser.parse(xml)
            if (result is DavResult.Success) {
                responses = result.value.responses
                println("Responses: ${responses.size}")
                println("With calendar-data: ${responses.count { it.calendarData != null }}")
                val sample = responses.firstOrNull { it.calendarData != null }?.calendarData
                println("Sample calendar-data length: ${sample?.length ?: 0}")
            } else {
                println("Parse failed: $result")
            }
        }
        println("XML parse time: ${xmlParseTime}ms")
        
        // Step 2: Parse iCal data
        println("\n--- Step 2: ICalParser ---")
        val iCalParser = ICalParser()
        var eventCount = 0
        var parseErrors = 0
        
        val icalParseTime = measureTimeMillis {
            for (response in responses) {
                val calData = response.calendarData ?: continue
                val result = iCalParser.parseAllEvents(calData)
                if (result is ParseResult.Success) {
                    eventCount += result.value.size
                } else {
                    parseErrors++
                }
            }
        }
        println("iCal parse time: ${icalParseTime}ms")
        println("Events parsed: $eventCount")
        println("Parse errors: $parseErrors")
        
        println("\n--- Total ---")
        println("Total time: ${xmlParseTime + icalParseTime}ms")
    }
}
