package com.smartdocs.editor.core.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/** One purchasable plan shown on the paywall. */
data class ProPlan(val productId: String, val title: String, val price: String, val period: String, val offerToken: String, val details: ProductDetails, val hasTrial: Boolean)

/**
 * Google Play Billing (v9) wrapper: product query, purchase flow, restore, acknowledgement, entitlement caching.
 * Client mode: client-side verification only. Server mode ([LicenseClient.configured]): every purchase / restore is
 * sent to `/verify-purchase` (Play Developer API on the server) and the signed licence is stored; a refund or revoke
 * reported by the server removes Pro; while the server is unreachable a new purchase counts provisionally for 3 days.
 */
class BillingManager private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: BillingManager? = null
        fun get(context: Context): BillingManager = instance ?: synchronized(this) { instance ?: BillingManager(context.applicationContext).also { instance = it } }
    }

    sealed interface PurchaseEvent {
        data object Success : PurchaseEvent
        data object Pending : PurchaseEvent
        data object Cancelled : PurchaseEvent
        data class Error(val code: Int, val message: String) : PurchaseEvent
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectLock = Mutex()
    private val _plans = MutableStateFlow<List<ProPlan>>(emptyList())
    val plans: StateFlow<List<ProPlan>> = _plans
    private val _events = MutableStateFlow<PurchaseEvent?>(null)
    val events: StateFlow<PurchaseEvent?> = _events
    fun consumeEvent() { _events.value = null }

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener { result, purchases -> onPurchasesUpdated(result, purchases) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private suspend fun connect(): Boolean = connectLock.withLock {
        if (client.isReady) return@withLock true
        suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) { if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK) }
                override fun onBillingServiceDisconnected() { if (cont.isActive) cont.resume(false) }
            })
        }
    }

    /** Loads the subscription plans configured in Play Console. Empty when Play is unavailable or products are missing. */
    suspend fun loadPlans(): List<ProPlan> {
        if (!connect()) return emptyList()
        val params = QueryProductDetailsParams.newBuilder().setProductList(ProProducts.ALL.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build()
        }).build()
        val details: List<ProductDetails> = suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { result, res ->
                if (cont.isActive) cont.resume(if (result.responseCode == BillingClient.BillingResponseCode.OK) res.productDetailsList else emptyList())
            }
        }
        val plans = details.mapNotNull { d ->
            val offers = d.subscriptionOfferDetails ?: return@mapNotNull null
            // prefer an offer with a free-trial phase, else the base plan
            val offer = offers.firstOrNull { o -> o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L } } ?: offers.firstOrNull() ?: return@mapNotNull null
            val recurring = offer.pricingPhases.pricingPhaseList.lastOrNull() ?: return@mapNotNull null
            ProPlan(d.productId, d.name, recurring.formattedPrice, recurring.billingPeriod, offer.offerToken, d,
                hasTrial = offer.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L })
        }.sortedBy { ProProducts.ALL.indexOf(it.productId) }
        _plans.value = plans
        return plans
    }

    /** Opens the Play purchase sheet. The outcome arrives on [events]. */
    fun purchase(activity: Activity, plan: ProPlan): Boolean {
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(plan.details).setOfferToken(plan.offerToken).build()
        )).build()
        val r = client.launchBillingFlow(activity, params)
        if (r.responseCode != BillingClient.BillingResponseCode.OK) { _events.value = PurchaseEvent.Error(r.responseCode, r.debugMessage); return false }
        return true
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> scope.launch { handle(purchases.orEmpty(), fromFlow = true) }
            BillingClient.BillingResponseCode.USER_CANCELED -> _events.value = PurchaseEvent.Cancelled
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> scope.launch { restore() }
            else -> _events.value = PurchaseEvent.Error(result.responseCode, result.debugMessage)
        }
    }

    /** Re-reads owned subscriptions from Play ("restore purchases"; also run on app start). Returns true when Pro is active. */
    suspend fun restore(): Boolean {
        if (!connect()) return Pro.load(context).activeAt(System.currentTimeMillis())
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        val res: Pair<Boolean, List<Purchase>> = suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(params) { result, list ->
                if (cont.isActive) cont.resume((result.responseCode == BillingClient.BillingResponseCode.OK) to list)
            }
        }
        if (!res.first) return Pro.load(context).activeAt(System.currentTimeMillis())
        return handle(res.second, fromFlow = false)
    }

    private suspend fun handle(purchases: List<Purchase>, fromFlow: Boolean): Boolean {
        val ours = purchases.filter { p -> p.products.any { it in ProProducts.ALL } }
        val active = ours.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        ours.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }.forEach { acknowledge(it) }
        val current = Pro.load(context)
        when {
            active != null && LicenseClient.configured -> verifyOnServer(active, current)
            active != null -> Pro.set(context, Pro.Entitlement(Pro.Source.PLAY, active.products.firstOrNull(), 0L, System.currentTimeMillis(), active.isAutoRenewing))
            current.source == Pro.Source.PLAY -> Pro.set(context, Pro.Entitlement()) // subscription ended
        }
        if (fromFlow) _events.value = when {
            active != null -> PurchaseEvent.Success
            ours.any { it.purchaseState == Purchase.PurchaseState.PENDING } -> PurchaseEvent.Pending
            else -> PurchaseEvent.Error(-100, "no purchase")
        }
        return Pro.load(context).activeAt(System.currentTimeMillis())
    }

    /** Server mode: exchanges the purchase token for a signed licence. */
    private suspend fun verifyOnServer(p: Purchase, current: Pro.Entitlement) {
        val product = p.products.firstOrNull() ?: return
        val reply = kotlinx.coroutines.withContext(Dispatchers.IO) {
            try { LicenseClient.verifyPurchase(context, product, p.purchaseToken) } catch (e: LicenseClient.NetworkException) { null }
        }
        val token = reply?.body?.optString("token").orEmpty()
        when {
            reply == null -> {
                // server unreachable: keep a still-valid signed licence, else count the purchase provisionally (3 days)
                if (current.source == Pro.Source.PLAY && current.license != null && current.activeAt(System.currentTimeMillis())) return
                if (current.source == Pro.Source.PLAY && current.provisional && current.verifiedAt > 0) return
                Pro.set(context, Pro.Entitlement(Pro.Source.PLAY, product, 0L, System.currentTimeMillis(), p.isAutoRenewing, provisional = true))
            }
            reply.ok && reply.body.optBoolean("active") && token.isNotEmpty() -> LicenseClient.store(context, token, p.purchaseToken)
            reply.ok || reply.status in 400..499 -> if (current.source == Pro.Source.PLAY) Pro.set(context, Pro.Entitlement()) // refunded / revoked / invalid
        }
    }

    private suspend fun acknowledge(p: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()
        suspendCancellableCoroutine { cont -> client.acknowledgePurchase(params) { if (cont.isActive) cont.resume(Unit) } }
    }

    /** Fire-and-forget refresh used at app start: Play restore, licence re-check when due, worker schedule. Never throws. */
    fun refreshAsync() {
        scope.launch {
            runCatching { restore() }
            if (LicenseClient.checkDue(context)) runCatching { LicenseClient.refresh(context) }
            runCatching { LicenseCheckWorker.schedule(context) }
        }
    }
}
