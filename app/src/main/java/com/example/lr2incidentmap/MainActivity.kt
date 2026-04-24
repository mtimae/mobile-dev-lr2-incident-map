package com.example.lr2incidentmap

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.lr2incidentmap.databinding.ActivityMainBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var myLocationMarker: Marker? = null
    private val incidentItems = mutableListOf<IncidentItem>()

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarseGranted = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (fineGranted || coarseGranted) {
                binding.tvStatus.text = "Статус: разрешение на геолокацию получено"
                centerOnUserLocation()
            } else {
                binding.tvStatus.text = "Статус: разрешение на геолокацию не выдано"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid_prefs", MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = packageName

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupMap()
        setupButtons()
        loadSavedIncidents()

        if (hasLocationPermission()) {
            centerOnUserLocation()
        } else {
            requestLocationPermissions()
        }
    }

    private fun setupMap() {
        binding.mapView.setMultiTouchControls(true)

        val mapController = binding.mapView.controller
        mapController.setZoom(14.0)

        val startPoint = GeoPoint(54.7388, 55.9721)
        mapController.setCenter(startPoint)

        binding.tvStatus.text = "Статус: карта загружена"
    }

    private fun setupButtons() {
        binding.btnMyLocation.setOnClickListener {
            if (hasLocationPermission()) {
                centerOnUserLocation()
            } else {
                requestLocationPermissions()
            }
        }

        binding.btnAddIncident.setOnClickListener {
            showAddIncidentDialog()
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    private fun requestLocationPermissions() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun centerOnUserLocation() {
        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            null
        ).addOnSuccessListener { location ->
            if (location != null) {
                val point = GeoPoint(location.latitude, location.longitude)

                binding.mapView.controller.animateTo(point)
                binding.mapView.controller.setZoom(17.0)

                showMyLocationMarker(point)

                binding.tvStatus.text = String.format(
                    Locale.US,
                    "Статус: текущее местоположение\nШирота: %.5f\nДолгота: %.5f",
                    location.latitude,
                    location.longitude
                )
            } else {
                binding.tvStatus.text = "Статус: не удалось определить местоположение"
            }
        }.addOnFailureListener {
            binding.tvStatus.text = "Статус: ошибка получения местоположения"
        }
    }

    private fun showMyLocationMarker(point: GeoPoint) {
        if (myLocationMarker == null) {
            myLocationMarker = Marker(binding.mapView)
            binding.mapView.overlays.add(myLocationMarker)
        }

        myLocationMarker?.position = point
        myLocationMarker?.title = "Моё местоположение"
        myLocationMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        myLocationMarker?.icon =
            ContextCompat.getDrawable(this, android.R.drawable.ic_menu_mylocation)

        binding.mapView.invalidate()
    }

    private fun showAddIncidentDialog() {
        val items = arrayOf("ДТП", "Ремонт дороги")

        AlertDialog.Builder(this)
            .setTitle("Выберите тип происшествия")
            .setItems(items) { _, which ->
                val center = binding.mapView.mapCenter
                val point = GeoPoint(center.latitude, center.longitude)

                when (which) {
                    0 -> addIncident(point, "ДТП")
                    1 -> addIncident(point, "Ремонт дороги")
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun addIncident(point: GeoPoint, type: String) {
        val marker = Marker(binding.mapView)
        marker.position = point
        marker.title = type
        marker.snippet = String.format(
            Locale.US,
            "Широта: %.5f, Долгота: %.5f",
            point.latitude,
            point.longitude
        )
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        marker.icon = getIncidentIcon(type)

        binding.mapView.overlays.add(marker)

        incidentItems.add(
            IncidentItem(
                type = type,
                latitude = point.latitude,
                longitude = point.longitude
            )
        )

        saveIncidents()
        binding.mapView.invalidate()

        binding.tvStatus.text = String.format(
            Locale.US,
            "Статус: добавлена точка \"%s\"\nШирота: %.5f\nДолгота: %.5f",
            type,
            point.latitude,
            point.longitude
        )
    }

    private fun getIncidentIcon(type: String): Drawable? {
        return if (type == "ДТП") {
            ContextCompat.getDrawable(this, android.R.drawable.ic_dialog_alert)
        } else {
            ContextCompat.getDrawable(this, android.R.drawable.ic_menu_manage)
        }
    }

    private fun saveIncidents() {
        val jsonArray = JSONArray()

        incidentItems.forEach { item ->
            val jsonObject = JSONObject()
            jsonObject.put("type", item.type)
            jsonObject.put("latitude", item.latitude)
            jsonObject.put("longitude", item.longitude)
            jsonArray.put(jsonObject)
        }

        getSharedPreferences("incident_storage", MODE_PRIVATE)
            .edit()
            .putString("incident_list", jsonArray.toString())
            .apply()
    }

    private fun loadSavedIncidents() {
        val jsonString = getSharedPreferences("incident_storage", MODE_PRIVATE)
            .getString("incident_list", null)

        if (jsonString.isNullOrEmpty()) return

        incidentItems.clear()

        val jsonArray = JSONArray(jsonString)
        for (i in 0 until jsonArray.length()) {
            val jsonObject = jsonArray.getJSONObject(i)

            val item = IncidentItem(
                type = jsonObject.getString("type"),
                latitude = jsonObject.getDouble("latitude"),
                longitude = jsonObject.getDouble("longitude")
            )

            incidentItems.add(item)

            val marker = Marker(binding.mapView)
            marker.position = GeoPoint(item.latitude, item.longitude)
            marker.title = item.type
            marker.snippet = String.format(
                Locale.US,
                "Широта: %.5f, Долгота: %.5f",
                item.latitude,
                item.longitude
            )
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.icon = getIncidentIcon(item.type)

            binding.mapView.overlays.add(marker)
        }

        binding.mapView.invalidate()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }
}

data class IncidentItem(
    val type: String,
    val latitude: Double,
    val longitude: Double
)