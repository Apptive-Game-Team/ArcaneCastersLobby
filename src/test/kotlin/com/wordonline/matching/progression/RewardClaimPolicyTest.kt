package com.wordonline.matching.progression

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import

class RewardClaimPolicyTest {
    @TestConfiguration(proxyBeanMethods = false)
    @ConfigurationPropertiesScan(basePackageClasses = [RewardsProperties::class])
    @Import(RewardClaimPolicy::class)
    class ClaimPolicyConfiguration

    @Test
    fun `설정 스캔은 수동 수령 flag를 실제 policy bean에 주입한다`() {
        val runner = ApplicationContextRunner().withUserConfiguration(ClaimPolicyConfiguration::class.java)
        runner.run { context -> assertThat(context.getBean(RewardClaimPolicy::class.java).manual("manual-v1")).isFalse() }
        runner.withPropertyValues("rewards.manual-claims-enabled=true").run { context ->
            assertThat(context.getBean(RewardClaimPolicy::class.java).manual("manual-v1")).isTrue()
            assertThat(context.getBean(RewardClaimPolicy::class.java).manual(null)).isFalse()
        }
    }
    @Test
    fun `수동 수령은 기능 설정과 정확한 capability가 함께 있어야 활성화된다`() {
        val disabled = RewardClaimPolicy()
        val enabled = RewardClaimPolicy(RewardsProperties(manualClaimsEnabled = true))
        assertThat(disabled.manual("manual-v1")).isFalse()
        assertThat(enabled.manual(null)).isFalse()
        assertThat(enabled.manual("manual-v2")).isFalse()
        assertThat(enabled.manual("manual-v1")).isTrue()
    }
}
