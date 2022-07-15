package com.example.weatherapp

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.karumi.dexter.Dexter
import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationRequest
import android.net.Uri
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import com.example.weatherapp.models.Weather
import com.example.weatherapp.models.WeatherResponse
import com.example.weatherapp.network.WeatherService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.gson.Gson
import com.karumi.dexter.DexterBuilder
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import kotlinx.android.synthetic.main.activity_main.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var mFusedLocationClient: FusedLocationProviderClient
    private var mProgressDialog: Dialog? = null
    private lateinit var mSharedPreferences: SharedPreferences


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        mSharedPreferences = getSharedPreferences(Constants.PREFERENCE_NAME, Context.MODE_PRIVATE)
        // msharedpreferences의 정보들은 다른 앱을 통해 볼 수 없고, 이 앱을 통해서만 볼 수 있음

        setupUI()

        if (!isLocationEnabled()) {
            Toast.makeText(this, "GPS 설정이 꺼져있습니다.", Toast.LENGTH_SHORT).show()

            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            startActivity(intent)
        } else {
            Dexter.withActivity(this)
                .withPermissions(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                .withListener(object : MultiplePermissionsListener {
                    override fun onPermissionsChecked(report: MultiplePermissionsReport?) {
                        // 모든 권한이 허용됐을 때
                        if (report!!.areAllPermissionsGranted()) {
                            requestLocationData()
                        }
                        // 권한 중 하나라도 거부됐을 때
                        if (report.isAnyPermissionPermanentlyDenied) {
                            Toast.makeText(
                                this@MainActivity,
                                "위치 권한을 허용해주세요", Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    override fun onPermissionRationaleShouldBeShown(
                        permissions: MutableList<PermissionRequest>?,
                        token: PermissionToken?
                    ) {
                        showRationalDialogForPermissions()
                    }
                }).onSameThread()
                .check()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationData() {
        val mLocationRequest = com.google.android.gms.location.LocationRequest()
        mLocationRequest.priority =
            com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY

        mFusedLocationClient.requestLocationUpdates(
            mLocationRequest, mLocationCallback,
            Looper.myLooper()
        )
    }

    private val mLocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val mLastLocation: Location = locationResult.lastLocation      // 사용자 마지막 위치
            val latitude = mLastLocation.latitude
            Log.i("현재 위도", "$latitude")

            val longitude = mLastLocation.longitude
            Log.i("현재 경도", "$longitude")

            getLocationWeatherDetails(latitude, longitude)
        }
    }

    private fun getLocationWeatherDetails(latitude: Double, longitude: Double) {
        if (Constants.isNetworkAvailable(this)) {     // 인터넷이 연결 되었으면

            // URL 준비
            val retrofit: Retrofit = Retrofit.Builder()
                .baseUrl(Constants.BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())     // Gson 형식으로 결과물 받음
                .build()

            // 날씨 서비스 구축
            val service: WeatherService = retrofit
                .create<WeatherService>(WeatherService::class.java)

            // service를 바탕으로 리스트 요청을 시작해서 정보를 전달
            val listCall: Call<WeatherResponse> = service.getWeather(
                latitude, longitude, Constants.METRIC_UNIT, Constants.APP_ID
            )

            showCustomProgressDialog()

            // enqueue: retrofit과 함께 오는 요청 인터페이스의 일부
            listCall.enqueue(object : Callback<WeatherResponse> {
                // 응답 성공
                override fun onResponse(
                    call: Call<WeatherResponse>,
                    response: Response<WeatherResponse>
                ) {
                    if (response!!.isSuccessful) {

                        hideProgressDialog()

                        val weatherList: WeatherResponse? = response.body()

                        val weatherResponseJsonString = Gson().toJson(weatherList)
                        val editor = mSharedPreferences.edit()
                        editor.putString(Constants.WEATHER_RESPONSE_DATA, weatherResponseJsonString)
                        // WEATHER_RESPONSE_DATA에 weatherResponseJsonString 저장
                        editor.apply()

                        if (weatherList != null) {
                            setupUI()
                        }
                        Log.i("Response Result", "'$weatherList")
                    } else {
                        val rc = response.code()
                        when (rc) {
                            400 -> {
                                Log.e("Error 400", "연결 상태 불량")
                            }
                            404 -> {
                                Log.e("Error 404", "요청한 페이지 찾을 수 없음")
                            }
                            else -> {
                                Log.e("Error", "알 수 없는 오류 발생")
                            }
                        }
                    }
                }

                // 응답 실패
                override fun onFailure(call: Call<WeatherResponse>, t: Throwable) {
                    Log.e("Error!", t!!.message.toString())
                    hideProgressDialog()
                }
            })
        } else {
            Toast.makeText(
                this@MainActivity,
                "인터넷 연결 안 됨", Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showRationalDialogForPermissions() {
        AlertDialog.Builder(this)
            .setMessage("필요한 권한이 거부되었습니다. 설정에서 권한을 활성화할 수 있습니다.")
            .setPositiveButton(
                "설정"
            ) { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    val uri = Uri.fromParts("package", packageName, null)
                    intent.data = uri
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    e.printStackTrace()
                }
            }
            .setNegativeButton("취소") { dialog,
                                       _ ->
                dialog.dismiss()
            }.show()
    }

    private fun isLocationEnabled(): Boolean {

        // 위치 관리
        val locationManager: LocationManager =
            getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // GPS? or Network?
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun showCustomProgressDialog() {
        mProgressDialog = Dialog(this)
        mProgressDialog!!.setContentView(R.layout.dialog_custom_progress)
        mProgressDialog!!.show()
    }

    private fun hideProgressDialog() {
        if (mProgressDialog != null) {
            mProgressDialog!!.dismiss()     // 작업을 중단하고 종료
        }
    }

    // 메뉴 추가
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when(item.itemId){
            R.id.action_refresh -> {
                requestLocationData()
                true
            }else -> super.onOptionsItemSelected(item)
        }
    }



    private fun setupUI() {

        val weatherResponseJsonString = mSharedPreferences
            .getString(Constants.WEATHER_RESPONSE_DATA, "")

        if(!weatherResponseJsonString.isNullOrEmpty()){
            val weatherList = Gson().fromJson(weatherResponseJsonString,
                WeatherResponse::class.java)

            // 비어있는지 아닌지
            for (i in weatherList.weather.indices) {
                Log.i("Weather Name", weatherList.weather.toString())

                tv_weather.text = weatherList.weather[i].main
                tv_condition.text = weatherList.weather[i].description
                tv_degree.text = weatherList.main.temp.toString() +
                        getUnit(application.resources.configuration.toString())  // 위치에 따라 온도 표시 변경
                tv_sunrise.text = unixTime(weatherList.sys.sunrise)
                tv_sunset.text = unixTime(weatherList.sys.sunset)
                tv_min.text = "min " + weatherList.main.temp_min.toString() + "ºC"
                tv_max.text = "max " +weatherList.main.temp_max.toString() + "ºC"
                tv_percent.text = weatherList.main.humidity.toString() + "%"
                tv_mh.text = weatherList.wind.speed.toString()
                tv_name.text = weatherList.name
                tv_country.text = weatherList.sys.country

                when(weatherList.weather[i].icon){
                    "01d" -> iv_weather.setImageResource(R.drawable.sunny)
                    "02d" -> iv_weather.setImageResource(R.drawable.cloud)
                    "03d" -> iv_weather.setImageResource(R.drawable.cloud)
                    "04d" -> iv_weather.setImageResource(R.drawable.cloud)
                    "05d" -> iv_weather.setImageResource(R.drawable.rainy)
                    "06d" -> iv_weather.setImageResource(R.drawable.storm)
                    "07d" -> iv_weather.setImageResource(R.drawable.snowflake)
                    "08d" -> iv_weather.setImageResource(R.drawable.cloud)
                    "09d" -> iv_weather.setImageResource(R.drawable.cloud)
                    "10d" -> iv_weather.setImageResource(R.drawable.cloud)
                    "11d" -> iv_weather.setImageResource(R.drawable.cloud)
                    "12d" -> iv_weather.setImageResource(R.drawable.rainy)
                    "13d" -> iv_weather.setImageResource(R.drawable.snowflake)
                }
            }
        }


    }

    private fun getUnit(value: String): String? {
        var value = "ºC"
        if ("US" == value || "LR" == value || "MM" == value) {
            value = "ºF"
        }
        return value
    }

    private fun unixTime(timex: Long): String? {
        val date = Date(timex * 1000L)
        val sdf = SimpleDateFormat("HH:mm", Locale.KOREA)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(date)
    }
}