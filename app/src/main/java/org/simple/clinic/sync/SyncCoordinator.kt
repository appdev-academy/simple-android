package org.simple.clinic.sync

import com.f2prateek.rx.preferences2.Preference
import org.simple.clinic.measure
import org.simple.clinic.patient.SyncStatus
import org.simple.clinic.util.Optional
import org.simple.clinic.util.toNullable
import timber.log.Timber
import javax.inject.Inject

class SyncCoordinator @Inject constructor() {

  fun <T : Any, P> push(
      repository: SynceableRepository<T, P>,
      pushNetworkCall: (List<T>) -> DataPushResponse
  ) {
    val repositoryName = repository.javaClass.simpleName

    val pendingSyncRecords = measure({ repository.recordsWithSyncStatus(SyncStatus.PENDING) }) {
      Timber.tag("SyncPerf").i("Fetch pending records from $repositoryName:${it}ms")
    }

    if (pendingSyncRecords.isNotEmpty()) {
      val response = measure({ pushNetworkCall(pendingSyncRecords) }) {
        Timber.tag("SyncPerf").i("Push $repositoryName:${it}ms")
      }

      measure({ repository.setSyncStatus(SyncStatus.PENDING, SyncStatus.DONE) }) {
        Timber.tag("SyncPerf").i("Set sync status to PENDING in $repositoryName:${it}ms")
      }

      val validationErrors = response.validationErrors
      measure({ handleValidationErrors(validationErrors, pendingSyncRecords, repository) }) {
        Timber.tag("SyncPerf").i("Handle validation errors in $repositoryName:${it}ms")
      }
    }
  }

  private fun <P, T : Any> handleValidationErrors(
      validationErrors: List<ValidationErrors>,
      pendingSyncRecords: List<T>,
      repository: SynceableRepository<T, P>
  ) {
    val recordIdsWithErrors = validationErrors.map { it.uuid }
    if (recordIdsWithErrors.isNotEmpty()) {
      val recordType = pendingSyncRecords.first().javaClass.simpleName
      Timber.e("Server sent validation errors when syncing $recordType : $validationErrors")

      repository.setSyncStatus(recordIdsWithErrors, SyncStatus.INVALID)
    }
  }

  fun <T : Any, P> pull(
      repository: SynceableRepository<T, P>,
      lastPullToken: Preference<Optional<String>>,
      batchSize: Int,
      pullNetworkCall: (String?) -> DataPullResponse<P>
  ) {
    var hasFetchedAllData = false
    val repositoryName = repository.javaClass.simpleName

    while (!hasFetchedAllData) {
      val processToken = measure({ lastPullToken.get().toNullable() }) {
        Timber.tag("SyncPerf").i("Fetch process token in $repositoryName:${it}ms")
      }

      val response = measure({ pullNetworkCall(processToken) }) {
        Timber.tag("SyncPerf").i("Fetch records in $repositoryName:${it}ms")
      }

      measure({ repository.mergeWithLocalData(response.payloads) }) {
        Timber.tag("SyncPerf").i("Merge local data in $repositoryName:${it}ms")
      }

      measure({ lastPullToken.set(Optional.of(response.processToken)) }) {
        Timber.tag("SyncPerf").i("Update process token in $repositoryName:${it}ms")
      }

      hasFetchedAllData = response.payloads.size < batchSize
    }
  }
}
