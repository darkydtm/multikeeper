package com.tonapps.settings.dev.features

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.tonapps.core.flags.FeatureManager
import com.tonapps.core.flags.GemWalletEnvironment
import com.tonapps.core.flags.WalletFeatureKey
import com.tonapps.core.helper.T
import ui.components.moon.MoonItemTitle
import ui.components.moon.MoonSmallItemTitle
import ui.components.moon.cell.TextCell
import ui.components.moon.container.MoonScaffold
import ui.theme.UIKit

@Composable
fun FeatureFlagsScreen(
    onBack: () -> Unit,
) {
    MoonScaffold(
        title = "Features",
        onBack = onBack,
        content = {
            Column {
                val context = LocalContext.current
                val isDebuggable = remember {
                    context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                }
                if (isDebuggable) {
                    var environment by remember { mutableStateOf(GemWalletEnvironment.get(context)) }
                    var expanded by remember { mutableStateOf(false) }

                    TextCell(
                        title = "Gem Wallet environment",
                        content = {
                            Box {
                                MoonItemTitle(
                                    text = environment.name,
                                    color = UIKit.colorScheme.text.secondary,
                                )
                                DropdownMenu(
                                    containerColor = UIKit.colorScheme.background.contentTint,
                                    shape = UIKit.shapes.large,
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false },
                                ) {
                                    GemWalletEnvironment.entries.forEach { option ->
                                        TextCell(
                                            title = option.name,
                                            titleColor = if (option == environment) {
                                                UIKit.colorScheme.text.accent
                                            } else {
                                                UIKit.colorScheme.text.primary
                                            },
                                            onClick = {
                                                environment = option
                                                GemWalletEnvironment.set(context, option)
                                                expanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        },
                        onClick = { expanded = true },
                    )
                }

                val keys = remember { WalletFeatureKey.entries }
                for (i in keys) {
                    var isEnabled by remember { mutableStateOf(FeatureManager.isEnabled(i)) }
                    val isOverridden = remember(isEnabled) { FeatureManager.isOverridden(i) }
                    val value = FeatureManager.getValue(i, "<empty>")
                    val setFeature = { newValue: Boolean ->
                        isEnabled = newValue
                        FeatureManager.setFeatureEnabled(i, isEnabled)
                        T.show("Feature is ${if (isEnabled) "enabled" else "disabled"}! Please reload app for correct behaviour!")
                    }

                    TextCell(
                        title = i.featureKey,
                        subtitle = value,
                        description = {
                            if (isOverridden) {
                                MoonSmallItemTitle(
                                    text = "Overridden: ${
                                        FeatureManager.getRemoteValue(
                                            i
                                        )
                                    }"
                                )
                            }
                        },
                        content = {
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = {
                                    setFeature(!isEnabled)
                                },
                            )
                        },
                        onClick = {
                            setFeature(!isEnabled)
                        },
                        onLongClick = {
                            FeatureManager.reset(i)
                            isEnabled = FeatureManager.isEnabled(i)
                            T.show("Feature $i is sync with remote!")
                        }
                    )
                }
            }
        },
    )
}
