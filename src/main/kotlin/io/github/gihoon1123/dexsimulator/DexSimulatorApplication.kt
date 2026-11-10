package io.github.gihoon1123.dexsimulator

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DexSimulatorApplication

fun main(args: Array<String>) {
    runApplication<DexSimulatorApplication>(*args)
}
