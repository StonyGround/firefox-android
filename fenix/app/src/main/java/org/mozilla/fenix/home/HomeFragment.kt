/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.home

import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.StrictMode
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.selector.privateTabs
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.selectedOrDefaultSearchEngine
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.storage.FrecencyThresholdOption
import mozilla.components.feature.tab.collections.TabCollection
import mozilla.components.feature.top.sites.TopSitesConfig
import mozilla.components.feature.top.sites.TopSitesFrecencyConfig
import mozilla.components.feature.top.sites.TopSitesProviderConfig
import mozilla.components.lib.state.ext.consumeFrom
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.R
import org.mozilla.fenix.browser.BrowserAnimator.Companion.getToolbarNavOptions
import org.mozilla.fenix.browser.browsingmode.BrowsingMode
import org.mozilla.fenix.components.appstate.AppAction
import org.mozilla.fenix.components.toolbar.ToolbarPosition
import org.mozilla.fenix.databinding.FragmentHomeBinding
import org.mozilla.fenix.ext.*
import org.mozilla.fenix.onboarding.FenixOnboarding
import org.mozilla.fenix.perf.MarkersFragmentLifecycleCallbacks
import org.mozilla.fenix.utils.Settings.Companion.TOP_SITES_PROVIDER_MAX_THRESHOLD
import org.mozilla.fenix.wallpapers.Wallpaper

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

    private val browsingModeManager get() = (activity as HomeActivity).browsingModeManager

    private val store: BrowserStore
        get() = requireComponents.core.store

    private val onboarding by lazy {
        requireComponents.strictMode.resetAfter(StrictMode.allowThreadDiskReads()) {
            FenixOnboarding(requireContext())
        }
    }

    private var lastAppliedWallpaperName: String = Wallpaper.defaultName

    override fun onCreate(savedInstanceState: Bundle?) {
        // DO NOT ADD ANYTHING ABOVE THIS getProfilerTime CALL!
        val profilerStartTime = requireComponents.core.engine.profiler?.getProfilerTime()

        super.onCreate(savedInstanceState)

        bundleArgs = args.toBundle()

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

    @Suppress("LongMethod", "ComplexMethod")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // DO NOT ADD ANYTHING ABOVE THIS getProfilerTime CALL!
        val profilerStartTime = requireComponents.core.engine.profiler?.getProfilerTime()

        super.onViewCreated(view, savedInstanceState)

        TabCounterBuilder(
            context = requireContext(),
            browsingModeManager = browsingModeManager,
            navController = findNavController(),
            tabCounter = binding.tabButton,
        ).build()

        consumeFrom(requireComponents.core.store) {
            updateTabCounter(it)
        }

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
    override fun onDestroyView() {
        super.onDestroyView()

        _binding = null
        bundleArgs.clear()
        lastAppliedWallpaperName = Wallpaper.defaultName
    }

    override fun onStart() {
        super.onStart()

        subscribeToTabCollections()

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

    @VisibleForTesting
    internal fun navigateToSearch() {
        val directions = HomeFragmentDirections.actionGlobalSearchDialog(
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
    }
}
