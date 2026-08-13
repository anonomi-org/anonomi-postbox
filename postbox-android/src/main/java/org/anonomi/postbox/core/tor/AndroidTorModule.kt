/*
 *     Briar Mailbox
 *     Copyright (C) 2021-2022  The Briar Project
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as
 *     published by the Free Software Foundation, either version 3 of the
 *     License, or (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package org.briarproject.mailbox.core.tor

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManager
import org.briarproject.mailbox.core.event.EventBus
import org.briarproject.mailbox.core.event.EventExecutor
import org.briarproject.mailbox.core.lifecycle.IoExecutor
import org.briarproject.mailbox.core.lifecycle.LifecycleManager
import org.briarproject.mailbox.core.server.WebServerManager
import org.briarproject.mailbox.core.settings.SettingsManager
import org.briarproject.mailbox.core.tor.TorConstants.CONTROL_PORT
import org.briarproject.mailbox.core.tor.TorConstants.SOCKS_PORT
import org.briarproject.onionwrapper.AndroidLocationUtilsFactory.createAndroidLocationUtils
import org.briarproject.onionwrapper.AndroidTorWrapper
import org.briarproject.onionwrapper.CircumventionProvider
import org.briarproject.onionwrapper.LocationUtils
import java.io.File
import java.util.concurrent.Executor
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal class AndroidTorModule {

    companion object {
        private const val LEGACY_OBFS4_EXECUTABLE = "obfs4proxy"
    }

    @Provides
    @Singleton
    fun provideAndroidTorPlugin(
        app: Application,
        @IoExecutor ioExecutor: Executor,
        @EventExecutor eventExecutor: Executor,
        settingsManager: SettingsManager,
        networkManager: NetworkManager,
        locationUtils: LocationUtils,
        circumventionProvider: CircumventionProvider,
        wakeLockManager: AndroidWakeLockManager,
        lifecycleManager: LifecycleManager,
        eventBus: EventBus,
        webServerManager: WebServerManager,
    ): TorPlugin {
        val architecture = this.architecture
        val torDir = app.getDir("tor", Context.MODE_PRIVATE)
        // Versions before 1.0.5 extracted the pluggable transport under its
        // own name. The wrapper only removes the one it installs itself, so
        // without this the old binary stays behind on upgrade.
        File(torDir, LEGACY_OBFS4_EXECUTABLE).delete()
        val tor = AndroidTorWrapper(
            app,
            wakeLockManager,
            ioExecutor,
            eventExecutor,
            architecture.orEmpty(),
            torDir,
            SOCKS_PORT,
            CONTROL_PORT
        )
        return TorPluginImpl(
            ioExecutor,
            settingsManager,
            networkManager,
            locationUtils,
            circumventionProvider,
            tor,
            architecture
        ) { webServerManager.port }.also {
            lifecycleManager.registerService(it)
            eventBus.addListener(it)
        }
    }

    /**
     * Null if we ship no Tor binary for this device. The wrapper runs the
     * binary out of the native library directory, so the name is only used to
     * decide whether to start Tor at all.
     */
    private val architecture: String?
        get() {
            for (abi in supportedArchitectures) {
                return when {
                    abi.startsWith("x86_64") -> "x86_64"
                    abi.startsWith("x86") -> "x86"
                    abi.startsWith("arm64") -> "arm64"
                    abi.startsWith("armeabi") -> "arm"
                    else -> continue
                }
            }
            return null
        }

    private val supportedArchitectures: List<String>
        get() = if (SDK_INT >= 21) {
            Build.SUPPORTED_ABIS.toList()
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(Build.CPU_ABI, Build.CPU_ABI2)
        }

    @Provides
    @Singleton
    fun provideLocationUtils(app: Application): LocationUtils =
        createAndroidLocationUtils(app)

    @Provides
    @Singleton
    fun provideNetworkManager(
        lifecycleManager: LifecycleManager,
        networkManager: AndroidNetworkManager,
    ): NetworkManager {
        lifecycleManager.registerService(networkManager)
        return networkManager
    }

}
