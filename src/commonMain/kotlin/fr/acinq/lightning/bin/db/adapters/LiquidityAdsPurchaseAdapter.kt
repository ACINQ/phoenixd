package fr.acinq.lightning.bin.db.adapters

import app.cash.sqldelight.ColumnAdapter
import fr.acinq.phoenix.types.db.LiquidityAds
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LiquidityAdsPurchaseAdapter : ColumnAdapter<LiquidityAds.Purchase, String> {
    override fun decode(databaseValue: String): LiquidityAds.Purchase {
        return Json.decodeFromString<LiquidityAds.Purchase>(databaseValue)
    }

    override fun encode(value: LiquidityAds.Purchase): String {
        return Json.encodeToString(value)
    }

}