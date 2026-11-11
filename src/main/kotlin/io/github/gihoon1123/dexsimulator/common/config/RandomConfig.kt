package io.github.gihoon1123.dexsimulator.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.random.Random

/** Single source of randomness so that simulations can be replayed with a seeded Random in tests. */
@Configuration
class RandomConfig {
    @Bean
    fun random(): Random = Random.Default
}
