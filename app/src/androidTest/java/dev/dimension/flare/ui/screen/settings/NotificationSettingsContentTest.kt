package dev.dimension.flare.ui.screen.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dimension.flare.data.model.appearance.GlobalAppearance
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.data.push.PushAccount
import dev.dimension.flare.data.push.PushAccountState
import dev.dimension.flare.data.push.PushCategory
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.component.LocalGlobalAppearance
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.createSampleUser
import dev.dimension.flare.ui.theme.FlareTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class NotificationSettingsContentTest {
    @get:Rule val compose = createComposeRule()
    private val mediaQueryIntegration = ComposeUiFlags.isMediaQueryIntegrationEnabled

    @Before fun setUp() {
        ComposeUiFlags.isMediaQueryIntegrationEnabled = true
    }

    private val alice = MicroBlogKey("alice", "example.social")
    private val bob = MicroBlogKey("bob", "nostr")
    private val carol = MicroBlogKey("carol", "example.social")

    private var setEnabledValue: Boolean? = null
    private var accountToggles = mutableListOf<Pair<MicroBlogKey, Boolean>>()
    private var categoryToggles = mutableListOf<Pair<PushCategory, Boolean>>()
    private var selectedDistributor: String? = null
    private var installClicked = 0
    private var openSettingsClicked = 0
    private var permissionRequests = 0
    private var pendingPermission: (() -> Unit)? = null

    @Test
    fun masterSwitchGatesEnableBehindPermissionAndDisablesDirectly() {
        val state = State()
        show(state)
        val masterSwitch = compose.onNodeWithContentDescription("Enable push notifications")
        masterSwitch.assertIsOff().performClick()
        compose.runOnIdle {
            assertEquals(1, permissionRequests)
            assertNull(setEnabledValue)
            pendingPermission?.invoke()
            assertEquals(true, setEnabledValue)
        }
        compose.runOnIdle { state.enabled = true }
        compose.onNodeWithContentDescription("Enable push notifications").performClick()
        compose.runOnIdle {
            assertEquals(1, permissionRequests)
            assertEquals(false, setEnabledValue)
        }
    }

    @Test
    fun permissionDeniedShowsSystemSettingsShortcut() {
        val state = State()
        show(state)
        val blockedText = "Notifications are blocked for Flare. Allow them in system settings."
        compose
            .onNodeWithText(blockedText)
            .assertDoesNotExist()
        compose.runOnIdle { state.permissionDenied = true }
        compose
            .onNodeWithText(blockedText)
            .assertIsDisplayed()
        compose.onNodeWithText("Open settings").performClick()
        compose.runOnIdle {
            assertEquals(1, openSettingsClicked)
            assertEquals(0, installClicked)
        }
    }

    @Test
    fun noDistributorShowsInstallPromptInsteadOfPicker() {
        show(State())
        compose.onNodeWithText("No UnifiedPush distributor installed").assertIsDisplayed()
        compose.onNodeWithText("Get ntfy").performClick()
        compose.runOnIdle {
            assertEquals(1, installClicked)
            assertNull(selectedDistributor)
        }
        compose.onNodeWithText("Choose distributor").assertDoesNotExist()
    }

    @Test
    fun singleDistributorShowsItsLabelWithoutPicker() {
        val state = State(distributors = listOf("io.heckel.ntfy"))
        state.distributorLabels = mapOf("io.heckel.ntfy" to "ntfy")
        show(state)
        compose.onNodeWithText("ntfy").assertIsDisplayed()
        compose.onNodeWithText("Choose distributor").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, installClicked) }
    }

    @Test
    fun pickDialogListsDistributorLabelsAndSelectsOne() {
        val state =
            State(
                distributors = listOf("io.heckel.ntfy", "app.alt.push"),
                currentDistributor = "io.heckel.ntfy",
            )
        state.distributorLabels = mapOf("io.heckel.ntfy" to "ntfy", "app.alt.push" to "AltPush")
        show(state)
        compose.onNodeWithText("ntfy").assertIsDisplayed()
        compose.onNodeWithText("Choose distributor").assertDoesNotExist()
        compose.onNodeWithText("Distributor").performClick()
        compose.onNodeWithText("Choose distributor").assertIsDisplayed()
        compose.onNodeWithText("AltPush").performClick()
        compose.runOnIdle {
            assertEquals("app.alt.push", selectedDistributor)
        }
        compose.onNodeWithText("Choose distributor").assertDoesNotExist()
    }

    @Test
    fun accountRowsShowStateStringsAndTogglePerAccount() {
        val state =
            State(
                accounts =
                    listOf(
                        PushAccount(
                            accountKey = alice,
                            platformId = "mastodon",
                            supported = true,
                            enabled = true,
                            state = PushAccountState.Active,
                            lastError = null,
                        ),
                        PushAccount(
                            accountKey = bob,
                            platformId = "nostr",
                            supported = false,
                            enabled = true,
                            state = PushAccountState.Unsupported,
                            lastError = null,
                        ),
                        PushAccount(
                            accountKey = carol,
                            platformId = "mastodon",
                            supported = true,
                            enabled = true,
                            state = PushAccountState.Error,
                            lastError = "boom",
                        ),
                    ),
            )
        show(state)
        compose.onNodeWithText("Active").assertIsDisplayed()
        compose.onNodeWithText("Not supported by this platform").assertIsDisplayed()
        compose.onNodeWithText("Error: boom").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(bob.toString()).assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription(alice.toString()).assertIsOn().performClick()
        compose.onNodeWithContentDescription(carol.toString()).assertIsOn().performClick()
        compose.runOnIdle {
            assertEquals(listOf(alice to false, carol to false), accountToggles)
        }
    }

    @Test
    fun accountRowShowsDisplayNameFromPresenterProfile() {
        val state =
            State(
                accounts =
                    listOf(
                        PushAccount(
                            accountKey = alice,
                            platformId = "mastodon",
                            supported = true,
                            enabled = true,
                            state = PushAccountState.Active,
                            lastError = null,
                        ),
                    ),
            )
        val user = createSampleUser().copy(key = alice, avatar = null)
        show(state)
        compose.onNodeWithText(alice.toString()).assertIsDisplayed()
        compose.runOnIdle { state.profiles = mapOf(alice to user) }
        compose.onNodeWithText(user.name.raw).assertIsDisplayed()
        compose.onNodeWithText(alice.toString()).assertDoesNotExist()
    }

    @Test
    fun categoryTogglesReflectAndReportSetMembership() {
        val state = State(categories = setOf(PushCategory.Mention, PushCategory.Poll))
        show(state)
        compose.onNodeWithContentDescription("Mentions and replies").assertIsOn()
        compose.onNodeWithContentDescription("New followers").assertIsOff().performClick()
        compose.onNodeWithContentDescription("Follow requests").assertIsOff()
        compose.onNodeWithContentDescription("Favourites and reactions").assertIsOff()
        compose.onNodeWithContentDescription("Boosts and quotes").assertIsOff()
        compose.onNodeWithContentDescription("Poll results").assertIsOn()
        compose.onNodeWithContentDescription("Other activity").assertIsOff()
        compose.onNodeWithContentDescription("Mentions and replies").performClick()
        compose.runOnIdle {
            assertEquals(listOf(PushCategory.Follow to true, PushCategory.Mention to false), categoryToggles)
        }
    }

    private fun show(state: State) {
        compose.setContent {
            CompositionLocalProvider(
                LocalGlobalAppearance provides GlobalAppearance(),
                LocalTimelineAppearance provides TimelineAppearance(),
            ) {
                FlareTheme(darkTheme = false, dynamicColor = false) {
                    NotificationSettingsContent(
                        state = state,
                        callbacks =
                            NotificationSettingsCallbacks(
                                onSetEnabled = { setEnabledValue = it },
                                onSetAccountEnabled = { accountKey, value ->
                                    accountToggles.add(accountKey to value)
                                },
                                onSetCategory = { category, value ->
                                    categoryToggles.add(category to value)
                                },
                                onSelectDistributor = { selectedDistributor = it },
                                onInstallNtfy = { installClicked++ },
                                onOpenSystemSettings = { openSettingsClicked++ },
                            ),
                        beforeEnable = { action ->
                            permissionRequests++
                            pendingPermission = action
                        },
                        onBack = {},
                    )
                }
            }
        }
    }

    private inner class State(
        enabled: Boolean = false,
        categories: Set<PushCategory> = emptySet(),
        accounts: List<PushAccount> = emptyList(),
        distributors: List<String> = emptyList(),
        currentDistributor: String? = null,
        permissionDenied: Boolean = false,
    ) : NotificationSettingsState {
        override var enabled by mutableStateOf(enabled)
        override var categories by mutableStateOf(categories)
        override var accounts by mutableStateOf(accounts)
        override var profiles by mutableStateOf(mapOf<MicroBlogKey, UiProfile?>())
        override var distributors by mutableStateOf(distributors)
        override var currentDistributor by mutableStateOf(currentDistributor)
        override var distributorLabels by mutableStateOf(mapOf<String, String>())
        override var permissionDenied by mutableStateOf(permissionDenied)
    }
}
