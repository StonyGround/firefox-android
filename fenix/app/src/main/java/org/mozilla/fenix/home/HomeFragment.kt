/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.home

import android.content.res.Configuration
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.StrictMode
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.TOP
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.menu.view.MenuButton
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.selector.privateTabs
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.searchEngines
import mozilla.components.browser.state.state.selectedOrDefaultSearchEngine
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.menu.Orientation
import mozilla.components.concept.menu.candidate.DrawableMenuIcon
import mozilla.components.concept.menu.candidate.TextMenuCandidate
import mozilla.components.concept.storage.FrecencyThresholdOption
import mozilla.components.concept.sync.AccountObserver
import mozilla.components.concept.sync.AuthType
import mozilla.components.concept.sync.OAuthAccount
import mozilla.components.feature.tab.collections.TabCollection
import mozilla.components.feature.top.sites.TopSitesConfig
import mozilla.components.feature.top.sites.TopSitesFeature
import mozilla.components.feature.top.sites.TopSitesFrecencyConfig
import mozilla.components.feature.top.sites.TopSitesProviderConfig
import mozilla.components.lib.state.ext.consumeFlow
import mozilla.components.lib.state.ext.consumeFrom
import mozilla.components.service.glean.private.NoExtras
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import mozilla.components.support.ktx.android.content.res.resolveAttribute
import mozilla.components.support.ktx.kotlinx.coroutines.flow.ifChanged
import org.mozilla.fenix.Config
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.R
import org.mozilla.fenix.addons.showSnackBar
import org.mozilla.fenix.browser.BrowserAnimator.Companion.getToolbarNavOptions
import org.mozilla.fenix.browser.browsingmode.BrowsingMode
import org.mozilla.fenix.components.FenixSnackbar
import org.mozilla.fenix.components.PrivateShortcutCreateManager
import org.mozilla.fenix.components.TabCollectionStorage
import org.mozilla.fenix.components.appstate.AppAction
import org.mozilla.fenix.components.toolbar.ToolbarPosition
import org.mozilla.fenix.databinding.FragmentHome2Binding
import org.mozilla.fenix.databinding.FragmentHomeBinding
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.containsQueryParameters
import org.mozilla.fenix.ext.hideToolbar
import org.mozilla.fenix.ext.nav
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.ext.settings
import org.mozilla.fenix.gleanplumb.DefaultMessageController
import org.mozilla.fenix.gleanplumb.MessagingFeature
import org.mozilla.fenix.home.mozonline.showPrivacyPopWindow
import org.mozilla.fenix.home.pocket.DefaultPocketStoriesController
import org.mozilla.fenix.home.pocket.PocketRecommendedStoriesCategory
import org.mozilla.fenix.home.recentbookmarks.RecentBookmarksFeature
import org.mozilla.fenix.home.recentbookmarks.controller.DefaultRecentBookmarksController
import org.mozilla.fenix.home.recentsyncedtabs.RecentSyncedTabFeature
import org.mozilla.fenix.home.recentsyncedtabs.controller.DefaultRecentSyncedTabController
import org.mozilla.fenix.home.recenttabs.RecentTabsListFeature
import org.mozilla.fenix.home.recenttabs.controller.DefaultRecentTabsController
import org.mozilla.fenix.home.recentvisits.RecentVisitsFeature
import org.mozilla.fenix.home.recentvisits.controller.DefaultRecentVisitsController
import org.mozilla.fenix.home.sessioncontrol.DefaultSessionControlController
import org.mozilla.fenix.home.sessioncontrol.SessionControlInteractor
import org.mozilla.fenix.home.topsites.DefaultTopSitesView
import org.mozilla.fenix.onboarding.FenixOnboarding
import org.mozilla.fenix.perf.MarkersFragmentLifecycleCallbacks
import org.mozilla.fenix.search.toolbar.SearchSelectorMenu
import org.mozilla.fenix.tabstray.TabsTrayAccessPoint
import org.mozilla.fenix.utils.Settings.Companion.TOP_SITES_PROVIDER_MAX_THRESHOLD
import org.mozilla.fenix.utils.ToolbarPopupWindow
import org.mozilla.fenix.utils.allowUndo
import org.mozilla.fenix.wallpapers.Wallpaper
import java.lang.ref.WeakReference
import kotlin.math.min

@Suppress("TooManyFunctions", "LargeClass")
class HomeFragment : Fragment() {
    private val args by navArgs<HomeFragmentArgs>()

    @VisibleForTesting
    internal lateinit var bundleArgs: Bundle

    @VisibleForTesting
    @Suppress("VariableNaming")
    internal var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeScreenViewModel by activityViewModels()

    private val snackbarAnchorView: View?
        get() = when (requireContext().settings().toolbarPosition) {
            ToolbarPosition.BOTTOM -> binding.toolbarLayout
            ToolbarPosition.TOP -> null
        }

    private val browsingModeManager get() = (activity as HomeActivity).browsingModeManager

    private val store: BrowserStore
        get() = requireComponents.core.store

    private val onboarding by lazy {
        requireComponents.strictMode.resetAfter(StrictMode.allowThreadDiskReads()) {
            FenixOnboarding(requireContext())
        }
    }

    private var appBarLayout: AppBarLayout? = null
    private lateinit var currentMode: CurrentMode

    private var lastAppliedWallpaperName: String = Wallpaper.defaultName

    private val topSitesFeature = ViewBoundFeatureWrapper<TopSitesFeature>()
    private val messagingFeature = ViewBoundFeatureWrapper<MessagingFeature>()
    private val recentTabsListFeature = ViewBoundFeatureWrapper<RecentTabsListFeature>()
    private val recentSyncedTabFeature = ViewBoundFeatureWrapper<RecentSyncedTabFeature>()
    private val recentBookmarksFeature = ViewBoundFeatureWrapper<RecentBookmarksFeature>()
    private val historyMetadataFeature = ViewBoundFeatureWrapper<RecentVisitsFeature>()

    override fun onCreate(savedInstanceState: Bundle?) {
        // DO NOT ADD ANYTHING ABOVE THIS getProfilerTime CALL!
        val profilerStartTime = requireComponents.core.engine.profiler?.getProfilerTime()

        super.onCreate(savedInstanceState)

        bundleArgs = args.toBundle()

        if (!onboarding.userHasBeenOnboarded() &&
            requireContext().settings().shouldShowPrivacyPopWindow &&
            Config.channel.isMozillaOnline
        ) {
            showPrivacyPopWindow(requireContext(), requireActivity())
        }

        // DO NOT MOVE ANYTHING BELOW THIS addMarker CALL!
        requireComponents.core.engine.profiler?.addMarker(
            MarkersFragmentLifecycleCallbacks.MARKER_NAME,
            profilerStartTime,
            "HomeFragment.onCreate",
        )
    }

    @Suppress("LongMethod")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        // DO NOT ADD ANYTHING ABOVE THIS getProfilerTime CALL!
        val profilerStartTime = requireComponents.core.engine.profiler?.getProfilerTime()

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val activity = activity as HomeActivity
        val components = requireComponents

        currentMode = CurrentMode(
            requireContext(),
            onboarding,
            browsingModeManager,
            ::dispatchModeChanges,
        )

        components.appStore.dispatch(AppAction.ModeChange(currentMode.getCurrentMode()))

        lifecycleScope.launch(IO) {
            if (requireContext().settings().showPocketRecommendationsFeature) {
                val categories = components.core.pocketStoriesService.getStories()
                    .groupBy { story -> story.category }
                    .map { (category, stories) ->
                        PocketRecommendedStoriesCategory(
                            category,
                            stories,
                        )
                    }

                components.appStore.dispatch(AppAction.PocketStoriesCategoriesChange(categories))

                if (requireContext().settings().showPocketSponsoredStories) {
                    components.appStore.dispatch(
                        AppAction.PocketSponsoredStoriesChange(
                            components.core.pocketStoriesService.getSponsoredStories(),
                        ),
                    )
                }
            } else {
                components.appStore.dispatch(AppAction.PocketStoriesClean)
            }
        }

        if (requireContext().settings().isExperimentationEnabled) {
            messagingFeature.set(
                feature = MessagingFeature(
                    appStore = requireComponents.appStore,
                ),
                owner = viewLifecycleOwner,
                view = binding.root,
            )
        }

        if (requireContext().settings().showTopSitesFeature) {
            topSitesFeature.set(
                feature = TopSitesFeature(
                    view = DefaultTopSitesView(
                        appStore = components.appStore,
                        settings = components.settings,
                    ),
                    storage = components.core.topSitesStorage,
                    config = ::getTopSitesConfig,
                ),
                owner = viewLifecycleOwner,
                view = binding.root,
            )
        }

        if (requireContext().settings().showRecentTabsFeature) {
            recentTabsListFeature.set(
                feature = RecentTabsListFeature(
                    browserStore = components.core.store,
                    appStore = components.appStore,
                ),
                owner = viewLifecycleOwner,
                view = binding.root,
            )

            if (requireContext().settings().enableTaskContinuityEnhancements) {
                recentSyncedTabFeature.set(
                    feature = RecentSyncedTabFeature(
                        context = requireContext(),
                        appStore = requireComponents.appStore,
                        syncStore = requireComponents.backgroundServices.syncStore,
                        storage = requireComponents.backgroundServices.syncedTabsStorage,
                        accountManager = requireComponents.backgroundServices.accountManager,
                        historyStorage = requireComponents.core.historyStorage,
                        coroutineScope = viewLifecycleOwner.lifecycleScope,
                    ),
                    owner = viewLifecycleOwner,
                    view = binding.root,
                )
            }
        }

        if (requireContext().settings().showRecentBookmarksFeature) {
            recentBookmarksFeature.set(
                feature = RecentBookmarksFeature(
                    appStore = components.appStore,
                    bookmarksUseCase = run {
                        requireContext().components.useCases.bookmarksUseCases
                    },
                    scope = viewLifecycleOwner.lifecycleScope,
                ),
                owner = viewLifecycleOwner,
                view = binding.root,
            )
        }

        if (requireContext().settings().historyMetadataUIFeature) {
            historyMetadataFeature.set(
                feature = RecentVisitsFeature(
                    appStore = components.appStore,
                    historyMetadataStorage = components.core.historyStorage,
                    historyHighlightsStorage = components.core.lazyHistoryStorage,
                    scope = viewLifecycleOwner.lifecycleScope,
                ),
                owner = viewLifecycleOwner,
                view = binding.root,
            )
        }

        updateLayout()

        activity.themeManager.applyStatusBarTheme(activity)

        // DO NOT MOVE ANYTHING BELOW THIS addMarker CALL!
        requireComponents.core.engine.profiler?.addMarker(
            MarkersFragmentLifecycleCallbacks.MARKER_NAME,
            profilerStartTime,
            "HomeFragment.onCreateView",
        )
        return binding.root
    }

    /**
     * Returns a [TopSitesConfig] which specifies how many top sites to display and whether or
     * not frequently visited sites should be displayed.
     */
    @VisibleForTesting
    internal fun getTopSitesConfig(): TopSitesConfig {
        val settings = requireContext().settings()
        return TopSitesConfig(
            totalSites = settings.topSitesMaxLimit,
            frecencyConfig = TopSitesFrecencyConfig(
                FrecencyThresholdOption.SKIP_ONE_TIME_PAGES,
            ) { !Uri.parse(it.url).containsQueryParameters(settings.frecencyFilterQuery) },
            providerConfig = TopSitesProviderConfig(
                showProviderTopSites = settings.showContileFeature,
                maxThreshold = TOP_SITES_PROVIDER_MAX_THRESHOLD,
                providerFilter = { topSite ->
                    when (store.state.search.selectedOrDefaultSearchEngine?.name) {
                        AMAZON_SEARCH_ENGINE_NAME -> topSite.title != AMAZON_SPONSORED_TITLE
                        EBAY_SPONSORED_TITLE -> topSite.title != EBAY_SPONSORED_TITLE
                        else -> true
                    }
                },
            ),
        )
    }

    private fun updateLayout() {
        when (requireContext().settings().toolbarPosition) {
            ToolbarPosition.TOP -> {
                binding.toolbarLayout.layoutParams = CoordinatorLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.MATCH_PARENT,
                    ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.TOP
                }
            }
            ToolbarPosition.BOTTOM -> {
            }
        }
    }

    @Suppress("LongMethod", "ComplexMethod")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // DO NOT ADD ANYTHING ABOVE THIS getProfilerTime CALL!
        val profilerStartTime = requireComponents.core.engine.profiler?.getProfilerTime()

        super.onViewCreated(view, savedInstanceState)

//        HomeMenuBuilder(
//            view = view,
//            context = view.context,
//            lifecycleOwner = viewLifecycleOwner,
//            homeActivity = activity as HomeActivity,
//            navController = findNavController(),
//            hideOnboardingIfNeeded = ::hideOnboardingIfNeeded,
//        ).build()

        TabCounterBuilder(
            context = requireContext(),
            browsingModeManager = browsingModeManager,
            navController = findNavController(),
            tabCounter = binding.tabButton,
        ).build()

        consumeFrom(requireComponents.core.store) {
            updateTabCounter(it)
        }

        homeViewModel.sessionToDelete?.also {
            if (it == ALL_NORMAL_TABS || it == ALL_PRIVATE_TABS) {
                removeAllTabsAndShowSnackbar(it)
            } else {
                removeTabAndShowSnackbar(it)
            }
        }

        homeViewModel.sessionToDelete = null

        updateTabCounter(requireComponents.core.store.state)

        binding.bgSearch.setOnClickListener {
            navigateToSearch()
        }

        if (bundleArgs.getBoolean(FOCUS_ON_ADDRESS_BAR)) {
            navigateToSearch()
        }

        // DO NOT MOVE ANYTHING BELOW THIS addMarker CALL!
        requireComponents.core.engine.profiler?.addMarker(
            MarkersFragmentLifecycleCallbacks.MARKER_NAME,
            profilerStartTime,
            "HomeFragment.onViewCreated",
        )
    }

    private fun removeAllTabsAndShowSnackbar(sessionCode: String) {
        if (sessionCode == ALL_PRIVATE_TABS) {
            requireComponents.useCases.tabsUseCases.removePrivateTabs()
        } else {
            requireComponents.useCases.tabsUseCases.removeNormalTabs()
        }

        val snackbarMessage = if (sessionCode == ALL_PRIVATE_TABS) {
            getString(R.string.snackbar_private_tabs_closed)
        } else {
            getString(R.string.snackbar_tabs_closed)
        }

        viewLifecycleOwner.lifecycleScope.allowUndo(
            requireView(),
            snackbarMessage,
            requireContext().getString(R.string.snackbar_deleted_undo),
            {
                requireComponents.useCases.tabsUseCases.undo.invoke()
            },
            operation = { },
            anchorView = snackbarAnchorView,
        )
    }

    private fun removeTabAndShowSnackbar(sessionId: String) {
        val tab = store.state.findTab(sessionId) ?: return

        requireComponents.useCases.tabsUseCases.removeTab(sessionId)

        val snackbarMessage = if (tab.content.private) {
            requireContext().getString(R.string.snackbar_private_tab_closed)
        } else {
            requireContext().getString(R.string.snackbar_tab_closed)
        }

        viewLifecycleOwner.lifecycleScope.allowUndo(
            requireView(),
            snackbarMessage,
            requireContext().getString(R.string.snackbar_deleted_undo),
            {
                requireComponents.useCases.tabsUseCases.undo.invoke()
                findNavController().navigate(
                    HomeFragmentDirections.actionGlobalBrowser(null),
                )
            },
            operation = { },
            anchorView = snackbarAnchorView,
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()

        appBarLayout = null
        _binding = null
        bundleArgs.clear()
        lastAppliedWallpaperName = Wallpaper.defaultName
    }

    override fun onStart() {
        super.onStart()

        subscribeToTabCollections()

        requireComponents.backgroundServices.accountManagerAvailableQueue.runIfReadyOrQueue {
            // By the time this code runs, we may not be attached to a context or have a view lifecycle owner.
            if ((this@HomeFragment).view?.context == null) {
                return@runIfReadyOrQueue
            }

            requireComponents.backgroundServices.accountManager.register(
                currentMode,
                owner = this@HomeFragment.viewLifecycleOwner,
            )
            requireComponents.backgroundServices.accountManager.register(
                object : AccountObserver {
                    override fun onAuthenticated(account: OAuthAccount, authType: AuthType) {
                        if (authType != AuthType.Existing) {
                            view?.let {
                                FenixSnackbar.make(
                                    view = it,
                                    duration = Snackbar.LENGTH_SHORT,
                                    isDisplayedWithBrowserToolbar = false,
                                )
                                    .setText(it.context.getString(R.string.onboarding_firefox_account_sync_is_on))
                                    .setAnchorView(binding.toolbarLayout)
                                    .show()
                            }
                        }
                    }
                },
                owner = this@HomeFragment.viewLifecycleOwner,
            )
        }

        // We only want this observer live just before we navigate away to the collection creation screen

        lifecycleScope.launch(IO) {
            requireComponents.reviewPromptController.promptReview(requireActivity())
        }
    }

    private fun dispatchModeChanges(mode: Mode) {
        if (mode != Mode.fromBrowsingMode(browsingModeManager.mode)) {
            requireContext().components.appStore.dispatch(AppAction.ModeChange(mode))
        }
    }

    override fun onResume() {
        super.onResume()
        if (browsingModeManager.mode == BrowsingMode.Private) {
            activity?.window?.setBackgroundDrawableResource(R.drawable.private_home_background_gradient)
        }

        hideToolbar()

        // Whenever a tab is selected its last access timestamp is automatically updated by A-C.
        // However, in the case of resuming the app to the home fragment, we already have an
        // existing selected tab, but its last access timestamp is outdated. No action is
        // triggered to cause an automatic update on warm start (no tab selection occurs). So we
        // update it manually here.
        requireComponents.useCases.sessionUseCases.updateLastAccess()
    }

    override fun onPause() {
        super.onPause()
        if (browsingModeManager.mode == BrowsingMode.Private) {
            activity?.window?.setBackgroundDrawable(
                ColorDrawable(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.fx_mobile_private_layer_color_1,
                    ),
                ),
            )
        }

        // Counterpart to the update in onResume to keep the last access timestamp of the selected
        // tab up-to-date.
        requireComponents.useCases.sessionUseCases.updateLastAccess()
    }

    private fun hideOnboardingIfNeeded() {
        if (!onboarding.userHasBeenOnboarded()) {
            onboarding.finish()
            requireContext().components.appStore.dispatch(
                AppAction.ModeChange(
                    mode = currentMode.getCurrentMode(),
                ),
            )
        }
    }

    @VisibleForTesting
    internal fun navigateToSearch() {
        val directions =
            HomeFragmentDirections.actionGlobalSearchDialog(
                sessionId = null,
            )

        nav(R.id.homeFragment, directions, getToolbarNavOptions(requireContext()))

    }

    private fun subscribeToTabCollections(): Observer<List<TabCollection>> {
        return Observer<List<TabCollection>> {
            requireComponents.core.tabCollectionStorage.cachedTabCollections = it
            requireComponents.appStore.dispatch(AppAction.CollectionsChange(it))
        }.also { observer ->
            requireComponents.core.tabCollectionStorage.getCollections().observe(this, observer)
        }
    }


    // TODO use [FenixTabCounterToolbarButton] instead of [TabCounter]:
    // https://github.com/mozilla-mobile/fenix/issues/16792
    private fun updateTabCounter(browserState: BrowserState) {
        val tabCount = if (browsingModeManager.mode.isPrivate) {
            browserState.privateTabs.size
        } else {
            browserState.normalTabs.size
        }

        binding.tabButton.setCountWithAnimation(tabCount)
        // The add_tabs_to_collections_button is added at runtime. We need to search for it in the same way.
    }

    companion object {
        const val ALL_NORMAL_TABS = "all_normal"
        const val ALL_PRIVATE_TABS = "all_private"

        private const val FOCUS_ON_ADDRESS_BAR = "focusOnAddressBar"

        // Sponsored top sites titles and search engine names used for filtering
        const val AMAZON_SPONSORED_TITLE = "Amazon"
        const val AMAZON_SEARCH_ENGINE_NAME = "Amazon.com"
        const val EBAY_SPONSORED_TITLE = "eBay"

        // Elevation for undo toasts
        internal const val TOAST_ELEVATION = 80f
    }
}
