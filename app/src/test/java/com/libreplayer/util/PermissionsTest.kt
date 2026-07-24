package com.libreplayer.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PermissionsTest {
    @Test
    fun `granted permission needs no action`() {
        assertThat(
            resolveAudioPermissionAction(
                hasPermission = true,
                deniedRequestCount = 1,
                shouldShowRationale = false,
            ),
        ).isNull()
    }

    @Test
    fun `fresh install without denials stays in app`() {
        assertThat(
            resolveAudioPermissionAction(
                hasPermission = false,
                deniedRequestCount = 0,
                shouldShowRationale = false,
            ),
        ).isEqualTo(AudioPermissionAction.REQUEST)
    }

    @Test
    fun `first denied request without rationale still stays in app`() {
        assertThat(
            resolveAudioPermissionAction(
                hasPermission = false,
                deniedRequestCount = 1,
                shouldShowRationale = false,
            ),
        ).isEqualTo(AudioPermissionAction.REQUEST)
    }

    @Test
    fun `denied permission with rationale stays in app`() {
        assertThat(
            resolveAudioPermissionAction(
                hasPermission = false,
                deniedRequestCount = 2,
                shouldShowRationale = true,
            ),
        ).isEqualTo(AudioPermissionAction.REQUEST)
    }

    @Test
    fun `repeated denial without rationale opens settings`() {
        assertThat(
            resolveAudioPermissionAction(
                hasPermission = false,
                deniedRequestCount = 2,
                shouldShowRationale = false,
            ),
        ).isEqualTo(AudioPermissionAction.OPEN_SETTINGS)
    }
}
