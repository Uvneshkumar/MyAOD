package uvnesh.myaod

import retrofit2.http.GET
import retrofit2.http.Query

interface WeatherService {

    @GET("weather")
    suspend fun getWeather(
        @Query("q") city: String = "Chennai",
        @Query("appid") apiKey: String = "4f5c5fb4b8696df240d8e3386a41df69",
        @Query("units") units: String = "metric"
    ): WeatherData

}