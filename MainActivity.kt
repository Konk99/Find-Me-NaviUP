package com.example.findmenaviup

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import java.util.Calendar
import kotlin.math.pow
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    @RequiresApi(Build.VERSION_CODES.R)
    companion object {
        private const val LOCATION_PER = 100
    }

    private lateinit var linkToWebsite: TextView
    private lateinit var scanningStatus: TextView
    private lateinit var closeTo: TextView
    private lateinit var pathFoundText: TextView

    @SuppressLint("MissingInflatedId", "SetTextI18n")
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.user_view)
        linkToWebsite = findViewById(R.id.linkToWebsite)
        scanningStatus = findViewById(R.id.scanningStatus)
        closeTo = findViewById(R.id.youAreHereText)
        pathFoundText = findViewById(R.id.pathFoundText)
        var isLongEnough = ""
        var pointNumber = 0
        val timeOfFirstScan = mutableListOf<Int>()

        val gson = Gson()

        val forkedPoints: String = applicationContext.assets.open("PointsSignalStrength.json").bufferedReader().use { it.readText() }
        val jsonPoints = gson.fromJson(forkedPoints, ObjectFromJson::class.java)!!

        val forkedCoordinates: String = applicationContext.assets.open("Coordinates.json").bufferedReader().use { it.readText() }
        val jsonCoordinates = gson.fromJson(forkedCoordinates, ObjectFromJson::class.java)!!

        val forkedReferencePoints: String = applicationContext.assets.open("Reference Points.json").bufferedReader().use { it.readText() }
        val jsonReferencePoints = gson.fromJson(forkedReferencePoints, ReferenceObject::class.java)!!

        val findButton = findViewById<Button>(R.id.findButton)

        findButton.setOnClickListener {
            //sprawdzenie czy użytkownik wyraził zgodę na korzystanie z jego lokalizacji przez aplikację
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED) {
                checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, 100)
            }else {
                //sprawdzenie czy wykonywany pomiar jest czwartym z rzędu
                if (pointNumber % 4 == 0 && pointNumber != 0) {
                    findButton.visibility = View.INVISIBLE
                    pathFoundText.text = "Wygląda na to że wykorzystałeś/-aś limit dozwolonych skanów. Poczekaj aż czas zejdzie do 0 i pokaże się przycisk aby ponownie wyznaczyć twoją pozycję."
                    pathFoundText.visibility = View.VISIBLE
                    closeTo.visibility = View.INVISIBLE

                    val currentTime = mutableListOf<Int>(Calendar.getInstance().get(Calendar.HOUR),Calendar.getInstance().get(Calendar.MINUTE),Calendar.getInstance().get(Calendar.SECOND))
                    val currentTimeInSeconds = (currentTime[0] * 3600 + currentTime[1] * 60 + currentTime[2]) * 1000
                    val timeSinceFirstScanInSecond = (timeOfFirstScan[0] * 3600 + timeOfFirstScan[1] * 60 + timeOfFirstScan[2]) * 1000
                    //sprawdzenie czy minęło odpowiednio dużo czasu od momentu pierwszego pomiaru
                    if((currentTimeInSeconds - timeSinceFirstScanInSecond).toLong() < 120_000) {
                        val timer = object: CountDownTimer(120_000 - (currentTimeInSeconds - timeSinceFirstScanInSecond).toLong(), 1_000) {
                            override fun onTick(remainingTime: Long) {
                                scanningStatus.visibility = View.INVISIBLE
                                linkToWebsite.visibility = View.VISIBLE
                                linkToWebsite.text = remainingTime.toString()
                            }

                            override fun onFinish() {
                                linkToWebsite.visibility = View.INVISIBLE
                                linkToWebsite.text = ""
                                scanningStatus.text = "Gotowy do skanowania"
                                scanningStatus.visibility = View.VISIBLE
                                findButton.visibility = View.VISIBLE
                                pathFoundText.visibility = View.INVISIBLE
                            }
                        }
                        timer.start()
                    }

                    timeOfFirstScan.clear()
                    isLongEnough = ""
                }else {
                    //ukrycie zbędnych części interfejsu użytkownika podczas skanowania
                    closeTo.visibility = View.INVISIBLE
                    linkToWebsite.visibility = View.INVISIBLE
                    pathFoundText.visibility = View.INVISIBLE
                    scanningStatus.text = "Skanowanie..."

                    val timeStart = System.currentTimeMillis()
                    val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                    val dictionary = mutableMapOf<String, MutableList<Int>>()

                    //wykonanie skanu sieci
                    wifiManager.startScan()
                    findButton.visibility = View.INVISIBLE

                    //jeżeli jest to pierwszy lub co czwarty skan z rzędu, zapisać czas skanu do zmiennej globalnej
                    if (isLongEnough == ""){
                        timeOfFirstScan.add(Calendar.getInstance().get(Calendar.HOUR))
                        timeOfFirstScan.add(Calendar.getInstance().get(Calendar.MINUTE))
                        timeOfFirstScan.add(Calendar.getInstance().get(Calendar.SECOND))
                        isLongEnough = Calendar.getInstance().get(Calendar.MINUTE).toString() + Calendar.getInstance().get(Calendar.SECOND).toString()
                    }

                    val handler = Handler()
                    val runnable = Runnable {
                        //wielokrotny zapis tych samych wartości skanu sieci w celu usunięcia pozostałości po ostatnim pomiarze
                        while (System.currentTimeMillis() < timeStart + 2_500) {
                            val wifiScanList = wifiManager.scanResults
                            for (wifi in wifiScanList) {
                                if (dictionary.containsKey(wifi.BSSID)) {
                                    dictionary[wifi.BSSID]?.add(wifi.level)
                                } else {
                                    dictionary[wifi.BSSID] = mutableListOf(wifi.level)
                                }
                            }
                        }
                        //sprawdzenie czy liczba uniaktowych BSSID jest większa niż 3
                        if (dictionary.keys.size > 3) {
                            val newDictionary = mutableMapOf<String, Float>()
                            //przypisanie do nowej zmmiennej wartości pochodzących z aktualnego skanu
                            dictionary.forEach { (name, values) ->
                                var numberOfRepeats = values.groupingBy { it }.eachCount().filter { it.value > 1 }
                                var sum = 0f
                                var uniqueValues = 0
                                var numberOfValues = 0
                                if (numberOfRepeats.keys.size > 1) {
                                    numberOfRepeats = numberOfRepeats.minus(numberOfRepeats.keys.elementAt(0))
                                }

                                numberOfRepeats.forEach { (strength, value) ->
                                    if (value > 60) {
                                        sum += strength
                                        uniqueValues++
                                        numberOfValues += value
                                    }
                                }

                                var mean = 0f
                                if(uniqueValues > 0){
                                    mean = sum / uniqueValues
                                }
                                newDictionary[name] = mean
                            }

                            val possiblePoints = mutableMapOf<String, Map<String, List<String>>>()
                            val meanOfDistances = mutableListOf<Float>()
                            val meanOfFinds = mutableListOf<Float>()

                            jsonPoints.map.forEach { (floor, floorList) ->
                                val distances = mutableMapOf<Float, String>()
                                val listOfSizes = mutableListOf<Int>()
                                floorList.forEach { (point, signalStrengths) ->
                                    val referenceValues = mutableListOf<Float>()
                                    val searchValues = mutableListOf<Float>()
                                    //znalezienie odpowiadających sobie numerów BSSID ze skanu aktualnego do tych znajdujacych się w poszczególnych punktach sieci
                                    signalStrengths.forEach { (bssid, value) ->
                                        if(newDictionary[bssid] != null){
                                            referenceValues.add(value)
                                            searchValues.add((newDictionary[bssid]!!))
                                        }
                                    }
                                    val size = referenceValues.size
                                    listOfSizes.add(size)
                                    if(size > 3){
                                        //obliczenie pseudoodległości od poszczególnych punktów sieci
                                        var sumOfPowers = 0f
                                        for (i in 0 until size) {
                                            sumOfPowers += (searchValues[i] - referenceValues[i]).pow(2) * searchValues[i] / -30f
                                        }
                                        distances[sqrt(sumOfPowers)] = point
                                    }
                                }
                                val meanOfSizes = listOfSizes.average().toFloat()
                                val sortedDistances = distances.toSortedMap()
                                //wybranie 4 punktów dla poszczególnych pięter mających najkrótsze pseudoodległości
                                if (sortedDistances.keys.size > 4){
                                    val onlyFourValues = sortedDistances.headMap(sortedDistances.keys.elementAt(4))
                                    val toSend = mutableMapOf<String, List<String>>()

                                    toSend["numbers"] = onlyFourValues.values.toList()
                                    toSend["size"] = listOf(meanOfSizes.toString())
                                    toSend["distance"] = listOf(onlyFourValues.keys.average().toFloat().toString())
                                    meanOfDistances.add(onlyFourValues.keys.average().toFloat())
                                    meanOfFinds.add(meanOfSizes)
                                    possiblePoints[floor] = toSend
                                }
                            }

                            var lowestIndex = 10f
                            val correctFloor = mutableMapOf<String, List<String>>()
                            //porównanie ze sobą pięter w celu wyznaczenia tego na którym najprawdopdobniej dokonano pomiaru
                            possiblePoints.forEach { (floor, attributes) ->
                                val sizeIndex = meanOfFinds.toSortedSet().reversed().indexOf(attributes["size"]?.get(0)!!.toFloat()) +1
                                val distanceIndex = meanOfDistances.toSortedSet().indexOf(attributes["distance"]?.get(0)!!.toFloat()) + 1
                                val meanIndex: Float = (sizeIndex.toFloat() * 1.1f + distanceIndex.toFloat()) / 2

                                if (meanIndex < lowestIndex) {
                                    lowestIndex = meanIndex
                                    correctFloor.clear()
                                    attributes["numbers"]?.let { it1 -> correctFloor.put(floor, it1) }
                                }
                            }

                            var meanX = 0f
                            var meanY = 0f
                            var meanZ = 0f
                            var closestRefPointDistance = 1000f
                            var closestRefPointName = ""
                            //uśrednienie współrzędnych i porównanie ich z tymi zapisanymi w punktach referencyjnych konkretnego piętra
                            correctFloor.forEach { (floor, numbers) ->
                                numbers.forEach { number ->
                                    meanX += jsonCoordinates.map[floor]?.get(number)?.get("X")!!
                                    meanY += jsonCoordinates.map[floor]?.get(number)?.get("Y")!!
                                    meanZ += jsonCoordinates.map[floor]?.get(number)?.get("Z")!!
                                }
                                meanX /= 4
                                meanY /= 4
                                meanZ /= 4

                                jsonReferencePoints.map[floor]!!.forEach { (_, thingies) ->
                                    val distance = sqrt((meanX - thingies["X"]!!.toFloat()).pow(2) + (meanY - thingies["Y"]!!.toFloat()).pow(2) + (meanZ - thingies["Z"]!!.toFloat()).pow(2))

                                    if (distance < closestRefPointDistance) {
                                        closestRefPointDistance = distance
                                        closestRefPointName = thingies["Punkt"].toString()
                                        val linkText = "naviup.github.io/#C1_" + thingies["Number"] + "_" + thingies["id"] + "_0"
                                        linkToWebsite.text = linkText
                                    }
                                }
                            }
                            closeTo.visibility = View.VISIBLE

                            if(closestRefPointName == "") {
                                closeTo.text = "Nie mogę cię znaleźć (´。＿。｀)"
                            } else {
                                closeTo.text = "Jesteś $closestRefPointName"

                                linkToWebsite.visibility = View.VISIBLE
                                pathFoundText.text = "Chcesz wyszukać drogi od tego miejsca? Kliknij tutaj!"
                                pathFoundText.visibility = View.VISIBLE
                            }
                            scanningStatus.text = "Gotowy do skanowania!"
                            findButton.visibility = View.VISIBLE

                            pointNumber += 1

                        }else {
                            pointNumber += 1

                            closeTo.visibility = View.VISIBLE
                            closeTo.text = "Za mała liczba widocznych sieci. Proszę ponów pomiar"
                            scanningStatus.text = "Gotowy do skanowania!"
                            findButton.visibility = View.VISIBLE
                        }
                    }
                    handler.postDelayed(runnable, 1000)
                }
            }
        }
    }

    private fun checkPermission(permission : String, requestCode: Int) {
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PER) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Location permission Granted", Toast.LENGTH_SHORT).show()
            }else {
                Toast.makeText(this, "Location permission Denied", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

data class ObjectFromJson(
    val map: Map<String, Map<String, Map<String, Float>>>,
)

data class ReferenceObject(
    val map: Map<String, Map<String, Map<String, String>>>
)