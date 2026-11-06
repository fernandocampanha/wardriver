package com.wardriver.sync

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class NetworkAdapter : RecyclerView.Adapter<NetworkAdapter.ViewHolder>() {

    private val items = mutableListOf<NetworkRecord>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ssid: TextView = view.findViewById(R.id.textSsid)
        val details: TextView = view.findViewById(R.id.textDetails)
    }



    fun submitList(newItems: Collection<NetworkRecord>) {
        items.clear()
        items.addAll(newItems.sortedByDescending { it.rssi })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_network, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.ssid.text = if (item.ssidHidden || item.ssid.isEmpty()) "(oculta)" else item.ssid

        val locationStr = if (item.lat != null && item.lon != null) {
            String.format(Locale.US, "%.5f, %.5f", item.lat, item.lon)
        } else {
            "sem fix GPS"
        }

        holder.details.text = "${item.bssid} · ch${item.channel} · ${item.rssi}dBm · " +
            "${item.encryption}${if (item.wpsActive) " · WPS" else ""} · $locationStr · " +
            "visto ${item.timesSeen}x"
    }

    override fun getItemCount(): Int = items.size
}
