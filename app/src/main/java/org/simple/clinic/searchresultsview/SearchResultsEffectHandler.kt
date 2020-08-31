package org.simple.clinic.searchresultsview

import com.spotify.mobius.rx2.RxMobius
import dagger.Lazy
import io.reactivex.ObservableTransformer
import org.simple.clinic.bp.BloodPressureMeasurement
import org.simple.clinic.facility.Facility
import org.simple.clinic.measure
import org.simple.clinic.patient.PatientRepository
import org.simple.clinic.patient.PatientSearchResult
import org.simple.clinic.util.scheduler.SchedulersProvider
import timber.log.Timber
import javax.inject.Inject

class SearchResultsEffectHandler @Inject constructor(
    private val schedulers: SchedulersProvider,
    private val patientRepository: PatientRepository,
    private val bloodPressureDao: BloodPressureMeasurement.RoomDao,
    private val currentFacility: Lazy<Facility>
) {

  fun build(): ObservableTransformer<SearchResultsEffect, SearchResultsEvent> {
    return RxMobius
        .subtypeEffectHandler<SearchResultsEffect, SearchResultsEvent>()
        .addTransformer(SearchWithCriteria::class.java, searchForPatients())
        .build()
  }

  private fun searchForPatients(): ObservableTransformer<SearchWithCriteria, SearchResultsEvent> {
    return ObservableTransformer { effects ->
      effects
          .observeOn(schedulers.io())
          .map { effect ->
            val searchResults = measure({ patientRepository.search(effect.searchCriteria) }) {
              Timber.tag("SearchPerf").i("Search by name: ${it}ms")
            }

            val currentFacility = measure({ currentFacility.get() }) {
              Timber.tag("SearchPerf").i("Fetch current facility: ${it}ms")
            }

            val partitioned = measure({ partitionSearchResultsByFacility(searchResults, currentFacility) }) {
              Timber.tag("SearchPerf").i("Partition search results: ${it}ms")
            }

            SearchResultsLoaded(partitioned)
          }
    }
  }

  private fun partitionSearchResultsByFacility(
      searchResults: List<PatientSearchResult>,
      facility: Facility
  ): PatientSearchResults {
    val patientIds = searchResults.map { it.uuid }

    val patientToFacilityIds = measure({ bloodPressureDao.patientToFacilityIds(patientIds) }) {
      Timber.tag("SearchPerf").i("Fetch patient facility IDs: ${it}ms")
    }

    return PatientSearchResults.from(
        searchResults = searchResults,
        patientToFacilityIds = patientToFacilityIds,
        currentFacility = facility
    )
  }
}
