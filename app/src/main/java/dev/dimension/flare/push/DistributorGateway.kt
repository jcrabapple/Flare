package dev.dimension.flare.push

import android.content.Context
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import org.unifiedpush.android.connector.UnifiedPush

/**
 * The [UnifiedPush] connector calls [PushRegistrar] makes, as an injectable seam so the registrar's
 * D9 reconciliation decisions can be tested with a fake distributor (the connector is a static
 * object). The Koin binding is the default [UnifiedPushGateway]; App.kt needs no change.
 */
internal interface DistributorGateway {
    /** Installed distributor package names. */
    fun getDistributors(): List<String>

    /** The saved distributor package name, or null when none is saved. */
    fun getSavedDistributor(): String?

    fun saveDistributor(packageName: String)

    fun register(
        instance: String,
        messageForDistributor: String,
        vapid: String,
    )

    fun unregister(instance: String)
}

@Single
internal class UnifiedPushGateway(
    @Provided private val context: Context,
) : DistributorGateway {
    override fun getDistributors(): List<String> = UnifiedPush.getDistributors(context)

    override fun getSavedDistributor(): String? = UnifiedPush.getSavedDistributor(context)

    override fun saveDistributor(packageName: String) {
        UnifiedPush.saveDistributor(context, packageName)
    }

    override fun register(
        instance: String,
        messageForDistributor: String,
        vapid: String,
    ) {
        UnifiedPush.register(context, instance, messageForDistributor, vapid)
    }

    override fun unregister(instance: String) {
        UnifiedPush.unregister(context, instance)
    }
}
