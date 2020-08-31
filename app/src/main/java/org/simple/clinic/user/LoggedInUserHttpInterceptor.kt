package org.simple.clinic.user

import dagger.Lazy
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.simple.clinic.facility.FacilityRepository
import org.simple.clinic.measure
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

class LoggedInUserHttpInterceptor @Inject constructor(
    private val facilityRepository: FacilityRepository,
    // TODO(vs): 2019-11-12 Fix this when creating multiple dagger scopes
    private val userSession: Lazy<UserSession>
) : Interceptor {

  override fun intercept(chain: Interceptor.Chain?): Response {
    val originalRequest = chain!!.request()

    val user = measure({ userSession.get().loggedInUserImmediate() }) {
      Timber.tag("SyncPerf").i("Fetch user in HTTP interceptor:${it}ms")
    }
    val (accessToken) = measure({ userSession.get().accessToken() }) {
      Timber.tag("SyncPerf").i("Fetch access token in HTTP interceptor:${it}ms")
    }

    var facilityUuid: UUID? = null
    if (user != null) {
      facilityUuid = measure({ facilityRepository.currentFacilityUuid() }) {
        Timber.tag("SyncPerf").i("Fetch current facility in HTTP interceptor:${it}ms")
      }
    }

    return if (user != null && accessToken.isNullOrBlank().not() && facilityUuid != null) {
      chain.proceed(addHeaders(originalRequest, accessToken!!, user, facilityUuid))
    } else {
      chain.proceed(originalRequest)
    }
  }

  private fun addHeaders(originalRequest: Request, accessToken: String, user: User, facilityUuid: UUID): Request {
    return originalRequest.newBuilder()
        .addHeader("Authorization", "Bearer $accessToken")
        .addHeader("X-USER-ID", user.uuid.toString())
        .addHeader("X-FACILITY-ID", facilityUuid.toString())
        .build()
  }

}
