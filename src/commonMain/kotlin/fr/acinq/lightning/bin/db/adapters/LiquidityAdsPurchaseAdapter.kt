package fr.acinq.lightning.bin.db.adapters

import app.cash.sqldelight.ColumnAdapter
import fr.acinq.lightning.wire.LiquidityAds
import fr.acinq.phoenix.types.db.LiquidityAdsDb
import fr.acinq.phoenix.types.db.LiquidityAdsDb.PurchaseDb.Companion.asDb
import fr.acinq.phoenix.types.db.LiquidityAdsDb.PurchaseDb.Companion.asOfficial
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LiquidityAdsPurchaseAdapter : ColumnAdapter<LiquidityAds.Purchase, String> {
    override fun decode(databaseValue: String): LiquidityAds.Purchase {
        return Json.decodeFromString<LiquidityAdsDb.PurchaseDb>(databaseValue).asOfficial()
    }

    override fun encode(value: LiquidityAds.Purchase): String {
        return Json.encodeToString(value.asDb())
    }

}