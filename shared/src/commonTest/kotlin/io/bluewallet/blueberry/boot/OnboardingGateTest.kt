package io.bluewallet.blueberry.boot

import io.bluewallet.blueberry.wallet.WalletSecretInspection
import kotlin.test.Test
import kotlin.test.assertEquals

class OnboardingGateTest {
    @Test
    fun invalid_secret_always_exits() {
        assertEquals(
            OnboardingGate.ExitInvalid("bad"),
            resolveOnboardingGate(
                WalletSecretInspection.Invalid("bad"),
                SyncFromYearInspection.Ok(2019),
            ),
        )
    }

    @Test
    fun missing_secret_is_full_onboarding() {
        assertEquals(
            OnboardingGate.Onboard(startAtYearStep = false),
            resolveOnboardingGate(
                WalletSecretInspection.Missing,
                SyncFromYearInspection.Missing,
            ),
        )
    }

    @Test
    fun secret_ok_year_missing_is_year_step_only() {
        assertEquals(
            OnboardingGate.Onboard(startAtYearStep = true),
            resolveOnboardingGate(
                WalletSecretInspection.Ok("zpub…"),
                SyncFromYearInspection.Missing,
            ),
        )
    }

    @Test
    fun both_ok_starts_app() {
        assertEquals(
            OnboardingGate.Start,
            resolveOnboardingGate(
                WalletSecretInspection.Ok("zpub…"),
                SyncFromYearInspection.Ok(2015),
            ),
        )
    }
}
