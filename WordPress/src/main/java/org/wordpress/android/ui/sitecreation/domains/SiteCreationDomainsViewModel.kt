package org.wordpress.android.ui.sitecreation.domains

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wordpress.android.R
import org.wordpress.android.fluxc.Dispatcher
import org.wordpress.android.fluxc.network.rest.wpcom.site.DomainSuggestionResponse
import org.wordpress.android.fluxc.store.SiteStore.OnSuggestedDomains
import org.wordpress.android.fluxc.store.SiteStore.SuggestDomainErrorType
import org.wordpress.android.models.networkresource.ListState
import org.wordpress.android.models.networkresource.ListState.Error
import org.wordpress.android.models.networkresource.ListState.Loading
import org.wordpress.android.models.networkresource.ListState.Ready
import org.wordpress.android.models.networkresource.ListState.Success
import org.wordpress.android.modules.BG_THREAD
import org.wordpress.android.modules.UI_THREAD
import org.wordpress.android.ui.sitecreation.domains.SiteCreationDomainsViewModel.DomainSuggestionsQuery.UserQuery
import org.wordpress.android.ui.sitecreation.domains.SiteCreationDomainsViewModel.DomainsUiState.DomainsUiContentState
import org.wordpress.android.ui.sitecreation.domains.SiteCreationDomainsViewModel.ListItemUiState.New
import org.wordpress.android.ui.sitecreation.domains.SiteCreationDomainsViewModel.ListItemUiState.New.DomainUiState.Cost
import org.wordpress.android.ui.sitecreation.domains.SiteCreationDomainsViewModel.ListItemUiState.Old
import org.wordpress.android.ui.sitecreation.misc.SiteCreationErrorType
import org.wordpress.android.ui.sitecreation.misc.SiteCreationHeaderUiState
import org.wordpress.android.ui.sitecreation.misc.SiteCreationSearchInputUiState
import org.wordpress.android.ui.sitecreation.misc.SiteCreationTracker
import org.wordpress.android.ui.sitecreation.usecases.FETCH_DOMAINS_VENDOR_DOT
import org.wordpress.android.ui.sitecreation.usecases.FETCH_DOMAINS_VENDOR_MOBILE
import org.wordpress.android.ui.sitecreation.usecases.FetchDomainsUseCase
import org.wordpress.android.ui.utils.UiString
import org.wordpress.android.ui.utils.UiString.UiStringRes
import org.wordpress.android.ui.utils.UiString.UiStringResWithParams
import org.wordpress.android.ui.utils.UiString.UiStringText
import org.wordpress.android.util.NetworkUtilsWrapper
import org.wordpress.android.util.config.SiteCreationDomainPurchasingFeatureConfig
import org.wordpress.android.viewmodel.SingleLiveEvent
import javax.inject.Inject
import javax.inject.Named
import kotlin.coroutines.CoroutineContext
import kotlin.properties.Delegates

private const val THROTTLE_DELAY = 500L
private const val ERROR_CONTEXT = "domains"

@HiltViewModel
class SiteCreationDomainsViewModel @Inject constructor(
    private val networkUtils: NetworkUtilsWrapper,
    private val dispatcher: Dispatcher,
    private val domainSanitizer: SiteCreationDomainSanitizer,
    private val fetchDomainsUseCase: FetchDomainsUseCase,
    private val purchasingFeatureConfig: SiteCreationDomainPurchasingFeatureConfig,
    private val tracker: SiteCreationTracker,
    @Named(BG_THREAD) private val bgDispatcher: CoroutineDispatcher,
    @Named(UI_THREAD) private val mainDispatcher: CoroutineDispatcher
) : ViewModel(), CoroutineScope {
    private val job = Job()
    private var fetchDomainsJob: Job? = null
    override val coroutineContext: CoroutineContext
        get() = bgDispatcher + job
    private var isStarted = false

    private val _uiState: MutableLiveData<DomainsUiState> = MutableLiveData()
    val uiState: LiveData<DomainsUiState> = _uiState

    private var currentQuery: DomainSuggestionsQuery? = null
    private var listState: ListState<DomainModel> = ListState.Init()
    private var selectedDomain by Delegates.observable<DomainModel?>(null) { _, old, new ->
        if (old != new) {
            updateUiStateToContent(currentQuery, listState)
        }
    }

    private val _createSiteBtnClicked = SingleLiveEvent<String>()
    val createSiteBtnClicked: LiveData<String> = _createSiteBtnClicked

    private val _clearBtnClicked = SingleLiveEvent<Unit>()
    val clearBtnClicked = _clearBtnClicked

    private val _onHelpClicked = SingleLiveEvent<Unit>()
    val onHelpClicked: LiveData<Unit> = _onHelpClicked

    init {
        dispatcher.register(fetchDomainsUseCase)
    }

    override fun onCleared() {
        super.onCleared()
        dispatcher.unregister(fetchDomainsUseCase)
    }

    fun start() {
        if (isStarted) return
        isStarted = true
        tracker.trackDomainsAccessed()
        resetUiState()
    }

    fun onCreateSiteBtnClicked() {
        val domain = requireNotNull(selectedDomain) {
            "Create site button should not be visible if a domain is not selected"
        }
        tracker.trackDomainSelected(domain.domainName, currentQuery?.value ?: "")
        _createSiteBtnClicked.value = domain.domainName
    }

    fun onClearTextBtnClicked() = _clearBtnClicked.call()

    fun onHelpClicked() = _onHelpClicked.call()

    fun onQueryChanged(query: String) = updateQuery(UserQuery(query))

    private fun updateQuery(query: DomainSuggestionsQuery?) {
        currentQuery = query
        selectedDomain = null
        fetchDomainsJob?.cancel() // cancel any previous requests
        if (query != null && query.value.isNotBlank()) {
            fetchDomains(query)
        } else {
            resetUiState()
        }
    }

    private fun resetUiState() = updateUiStateToContent(null, Ready(emptyList()))

    private fun fetchDomains(query: DomainSuggestionsQuery) {
        if (networkUtils.isNetworkAvailable()) {
            updateUiStateToContent(query, Loading(Ready(emptyList()), false))
            fetchDomainsJob = launch {
                delay(THROTTLE_DELAY)
                val onSuggestedDomains: OnSuggestedDomains = fetchDomainsByPurchasingFeatureConfig(query.value)

                withContext(mainDispatcher) {
                    onDomainsFetched(query, onSuggestedDomains)
                }
            }
        } else {
            tracker.trackErrorShown(ERROR_CONTEXT, SiteCreationErrorType.INTERNET_UNAVAILABLE_ERROR)
            updateUiStateToContent(
                query,
                Error(listState, errorMessageResId = R.string.no_network_message)
            )
        }
    }

    private suspend fun fetchDomainsByPurchasingFeatureConfig(query: String): OnSuggestedDomains {
        val onlyWordpressCom = !purchasingFeatureConfig.isEnabledOrManuallyOverridden()
        val vendor = if (onlyWordpressCom) FETCH_DOMAINS_VENDOR_DOT else FETCH_DOMAINS_VENDOR_MOBILE

        return fetchDomainsUseCase.fetchDomains(query, vendor, onlyWordpressCom)
    }

    private fun onDomainsFetched(query: DomainSuggestionsQuery, event: OnSuggestedDomains) {
        // We want to treat `INVALID_QUERY` as if it's an empty result, so we'll ignore it
        if (event.isError && event.error.type != SuggestDomainErrorType.INVALID_QUERY) {
            tracker.trackErrorShown(
                ERROR_CONTEXT,
                event.error.type.toString(),
                event.error.message
            )
            updateUiStateToContent(
                query,
                Error(
                    listState,
                    errorMessageResId = R.string.site_creation_fetch_suggestions_error_unknown
                )
            )
        } else {
            /**
             * We would like to show the domains that matches the current query at the top. For this, we split the
             * domains into two, one part for the domain names that start with the current query plus `.` and the
             * other part for the others. We then combine them back again into a single list.
             */
            val domains = event.suggestions.map(::parseSuggestion)
                .partition { it.domainName.startsWith("${query.value}.") }
                .toList().flatten()

            val isInvalidQuery = event.isError && event.error.type == SuggestDomainErrorType.INVALID_QUERY
            val emptyListMessage = UiStringRes(
                if (isInvalidQuery) R.string.new_site_creation_empty_domain_list_message_invalid_query
                else R.string.new_site_creation_empty_domain_list_message
            )

            updateUiStateToContent(query, Success(domains), emptyListMessage)
        }
    }

    private fun parseSuggestion(response: DomainSuggestionResponse): DomainModel = with(response) {
        DomainModel(
            domainName = domain_name,
            isFree = is_free,
            cost = cost.orEmpty(),
        )
    }

    private fun updateUiStateToContent(
        query: DomainSuggestionsQuery?,
        state: ListState<DomainModel>,
        emptyListMessage: UiString? = null
    ) {
        listState = state
        val isNonEmptyUserQuery = isNonEmptyUserQuery(query)

        _uiState.value = DomainsUiState(
            headerUiState = createHeaderUiState(
                !isNonEmptyUserQuery
            ),
            searchInputUiState = createSearchInputUiState(
                showProgress = state is Loading,
                showClearButton = isNonEmptyUserQuery,
                showDivider = state.data.isNotEmpty()
            ),
            contentState = createDomainsUiContentState(query, state, emptyListMessage),
            createSiteButtonContainerVisibility = getCreateSiteButtonState()
        )
    }

    private fun getCreateSiteButtonState(): Boolean {
        return selectedDomain?.isFree ?: false
    }

    private fun createDomainsUiContentState(
        query: DomainSuggestionsQuery?,
        state: ListState<DomainModel>,
        emptyListMessage: UiString?
    ): DomainsUiContentState {
        // Only treat it as an error if the search is user initiated
        val isError = isNonEmptyUserQuery(query) && state is Error

        val items = createSuggestionsUiStates(
            onRetry = { updateQuery(query) },
            query = query?.value,
            data = state.data,
            errorFetchingSuggestions = isError,
            errorResId = if (isError) (state as Error).errorMessageResId else null
        )
        return if (items.isEmpty()) {
            if (isNonEmptyUserQuery(query) && (state is Success || state is Ready)) {
                DomainsUiContentState.Empty(emptyListMessage)
            } else DomainsUiContentState.Initial
        } else {
            DomainsUiContentState.VisibleItems(items)
        }
    }

    private fun createSuggestionsUiStates(
        onRetry: () -> Unit,
        query: String?,
        data: List<DomainModel>,
        errorFetchingSuggestions: Boolean,
        @StringRes errorResId: Int?
    ): List<ListItemUiState> {
        val items: ArrayList<ListItemUiState> = ArrayList()
        if (errorFetchingSuggestions) {
            val errorUiState = Old.ErrorItemUiState(
                messageResId = errorResId ?: R.string.site_creation_fetch_suggestions_error_unknown,
                retryButtonResId = R.string.button_retry,
                onClick = onRetry,
            )
            items.add(errorUiState)
        } else {
            query?.let { value ->
                getDomainUnavailableUiState(value, data)?.let {
                    items.add(it)
                }
            }

            data.forEachIndexed { index, domain ->
                val itemUiState = createAvailableItemUiState(domain, index)
                items.add(itemUiState)
            }
        }
        return items
    }

    @Suppress("ForbiddenComment")
    private fun createAvailableItemUiState(domain: DomainModel, index: Int): ListItemUiState {
        return when (purchasingFeatureConfig.isEnabledOrManuallyOverridden()) {
            true -> New.DomainUiState(
                domain.domainName,
                cost = if (domain.isFree) Cost.Free else Cost.Paid(domain.cost), // TODO: Apply discounts
                variant = when (index) {
                    0 -> New.DomainUiState.Variant.Recommended
                    1 -> New.DomainUiState.Variant.BestAlternative
                    else -> null
                },
                onClick = { onDomainSelected(domain) },
            )
            else -> Old.DomainUiState.AvailableDomain(
                domainSanitizer.getName(domain.domainName),
                domainSanitizer.getDomain(domain.domainName),
                checked = domain == selectedDomain,
                onClick = { onDomainSelected(domain) }
            )
        }
    }

    @Suppress("ForbiddenComment", "ReturnCount")
    private fun getDomainUnavailableUiState(
        query: String,
        domains: List<DomainModel>
    ): ListItemUiState? {
        if (domains.isEmpty()) return null
        if (purchasingFeatureConfig.isEnabledOrManuallyOverridden()) return null // TODO: Add FQDN availability check
        val sanitizedQuery = domainSanitizer.sanitizeDomainQuery(query)
        val isDomainUnavailable = domains.none { it.domainName.startsWith("$sanitizedQuery.") }
        return if (isDomainUnavailable) {
            Old.DomainUiState.UnavailableDomain(
                sanitizedQuery,
                ".wordpress.com",
                UiStringRes(R.string.new_site_creation_unavailable_domain)
            )
        } else null
    }

    private fun createHeaderUiState(
        isVisible: Boolean
    ): SiteCreationHeaderUiState? {
        return if (isVisible) SiteCreationHeaderUiState(
            UiStringRes(R.string.new_site_creation_domain_header_title),
            UiStringRes(R.string.new_site_creation_domain_header_subtitle)
        ) else null
    }

    private fun createSearchInputUiState(
        showProgress: Boolean,
        showClearButton: Boolean,
        showDivider: Boolean,
    ): SiteCreationSearchInputUiState {
        return SiteCreationSearchInputUiState(
            hint = UiStringRes(R.string.new_site_creation_search_domain_input_hint),
            showProgress = showProgress,
            showClearButton = showClearButton,
            showDivider = showDivider,
            showKeyboard = true
        )
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun onDomainSelected(domain: DomainModel) {
        selectedDomain = domain.takeIf { it.isFree }
    }

    private fun isNonEmptyUserQuery(query: DomainSuggestionsQuery?) = query is UserQuery && query.value.isNotBlank()

    data class DomainModel(
        val domainName: String,
        val isFree: Boolean,
        val cost: String,
    )

    data class DomainsUiState(
        val headerUiState: SiteCreationHeaderUiState?,
        val searchInputUiState: SiteCreationSearchInputUiState,
        val contentState: DomainsUiContentState = DomainsUiContentState.Initial,
        val createSiteButtonContainerVisibility: Boolean
    ) {
        sealed class DomainsUiContentState(
            val emptyViewVisibility: Boolean,
            val exampleViewVisibility: Boolean,
            val items: List<ListItemUiState>
        ) {
            object Initial : DomainsUiContentState(
                emptyViewVisibility = false,
                exampleViewVisibility = true,
                items = emptyList()
            )

            class Empty(val message: UiString?) : DomainsUiContentState(
                emptyViewVisibility = true,
                exampleViewVisibility = false,
                items = emptyList()
            )

            class VisibleItems(items: List<ListItemUiState>) : DomainsUiContentState(
                emptyViewVisibility = false,
                exampleViewVisibility = false,
                items = items
            )
        }
    }

    sealed class ListItemUiState(open val type: Type) {
        enum class Type {
            DOMAIN_V1,
            DOMAIN_V2,
            ERROR_V1,
        }

        sealed class Old(override val type: Type) : ListItemUiState(type) {
            sealed class DomainUiState(
                open val name: String,
                open val domain: String,
                open val checked: Boolean,
                val radioButtonVisibility: Boolean,
                open val subTitle: UiString? = null,
            ) : Old(Type.DOMAIN_V1) {
                data class AvailableDomain(
                    override val name: String,
                    override val domain: String,
                    override val checked: Boolean,
                    val onClick: () -> Unit,
                ) : DomainUiState(name, domain, checked, true)

                data class UnavailableDomain(
                    override val name: String,
                    override val domain: String,
                    override val subTitle: UiString,
                ) : DomainUiState(name, domain, false, false, subTitle)
            }

            data class ErrorItemUiState(
                @StringRes val messageResId: Int,
                @StringRes val retryButtonResId: Int,
                val onClick: () -> Unit,
            ) : Old(Type.ERROR_V1)
        }

        sealed class New(override val type: Type) : ListItemUiState(type) {
            data class DomainUiState(
                val domainName: String,
                val cost: Cost,
                val onClick: () -> Unit,
                val variant: Variant? = null,
            ) : New(Type.DOMAIN_V2) {
                sealed class Variant(
                    @ColorRes val dotColor: Int,
                    @ColorRes val subtitleColor: Int? = null,
                    val subtitle: UiString,
                ) {
                    constructor(@ColorRes color: Int, subtitle: UiString) : this(color, color, subtitle)

                    object Unavailable : Variant(
                        R.color.red_50,
                        UiStringRes(R.string.site_creation_domain_tag_unavailable),
                    )

                    object Recommended : Variant(
                        R.color.jetpack_green_50,
                        UiStringRes(R.string.site_creation_domain_tag_recommended),
                    )

                    object BestAlternative : Variant(
                        R.color.purple_50,
                        UiStringRes(R.string.site_creation_domain_tag_best_alternative),
                    )

                    object Sale : Variant(
                        R.color.yellow_50,
                        UiStringRes(R.string.site_creation_domain_tag_sale)
                    )
                }

                sealed class Cost(val title: UiString) {
                    object Free : Cost(UiStringRes(R.string.free))

                    data class Paid(val cost: String) : Cost(
                        UiStringResWithParams(R.string.site_creation_domain_cost, UiStringText(cost))
                    )

                    data class OnSale(val titleCost: String, val subtitleCost: String) : Cost(
                        UiStringResWithParams(R.string.site_creation_domain_cost, UiStringText(titleCost))
                    ) {
                        val subtitle = UiStringResWithParams(
                            R.string.site_creation_domain_cost,
                            UiStringText(subtitleCost)
                        )
                    }
                }
            }
        }
    }

    /**
     * An organized way to separate user initiated searches from automatic searches so we can handle them differently.
     */
    private sealed class DomainSuggestionsQuery(val value: String) {
        /**
         * User initiated search.
         */
        class UserQuery(value: String) : DomainSuggestionsQuery(value)
    }
}
