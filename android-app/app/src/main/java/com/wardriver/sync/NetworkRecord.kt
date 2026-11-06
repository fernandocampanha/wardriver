package com.wardriver.sync

import androidx.room.Entity
import androidx.room.PrimaryKey





@Entity(tableName = "networks")
data class NetworkRecord(
    @PrimaryKey val bssid: String,
    var ssid: String,
    var ssidHidden: Boolean,
    var channel: Int,
    var rssi: Int,
    var encryption: String,
    var timesSeen: Long,
    var wpsActive: Boolean,
    var lat: Double?,
    var lon: Double?,
    var lastSessionId: String,
    var lastSeenAtMs: Long,
)
