package org.simple.clinic.patient

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.common.truth.Truth.assertThat
import io.reactivex.Completable
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.simple.clinic.AppDatabase
import org.simple.clinic.TestClinicApp
import org.simple.clinic.TestData
import org.simple.clinic.bloodsugar.BloodSugarMeasurement
import org.simple.clinic.bloodsugar.BloodSugarReading
import org.simple.clinic.bloodsugar.BloodSugarRepository
import org.simple.clinic.bloodsugar.Fasting
import org.simple.clinic.bloodsugar.Random
import org.simple.clinic.bp.BloodPressureMeasurement
import org.simple.clinic.bp.BloodPressureRepository
import org.simple.clinic.drugs.PrescriptionRepository
import org.simple.clinic.facility.Facility
import org.simple.clinic.facility.FacilityRepository
import org.simple.clinic.medicalhistory.Answer
import org.simple.clinic.medicalhistory.Answer.No
import org.simple.clinic.medicalhistory.Answer.Yes
import org.simple.clinic.medicalhistory.MedicalHistoryRepository
import org.simple.clinic.overdue.Appointment.AppointmentType
import org.simple.clinic.overdue.Appointment.AppointmentType.Automatic
import org.simple.clinic.overdue.Appointment.AppointmentType.Manual
import org.simple.clinic.overdue.Appointment.Status
import org.simple.clinic.overdue.Appointment.Status.Cancelled
import org.simple.clinic.overdue.Appointment.Status.Scheduled
import org.simple.clinic.overdue.Appointment.Status.Visited
import org.simple.clinic.overdue.AppointmentRepository
import org.simple.clinic.patient.PatientSearchCriteria.Name
import org.simple.clinic.patient.PatientSearchCriteria.PhoneNumber
import org.simple.clinic.patient.PatientSearchResult.LastSeen
import org.simple.clinic.patient.PatientStatus.Active
import org.simple.clinic.patient.PatientStatus.Dead
import org.simple.clinic.patient.PatientStatus.Inactive
import org.simple.clinic.patient.PatientStatus.Migrated
import org.simple.clinic.patient.PatientStatus.Unresponsive
import org.simple.clinic.patient.SyncStatus.DONE
import org.simple.clinic.patient.SyncStatus.PENDING
import org.simple.clinic.patient.businessid.BusinessId
import org.simple.clinic.patient.businessid.BusinessIdMetaData
import org.simple.clinic.patient.businessid.BusinessIdMetaDataAdapter
import org.simple.clinic.patient.businessid.Identifier
import org.simple.clinic.patient.businessid.Identifier.IdentifierType.BangladeshNationalId
import org.simple.clinic.patient.businessid.Identifier.IdentifierType.BpPassport
import org.simple.clinic.patient.businessid.Identifier.IdentifierType.Unknown
import org.simple.clinic.protocol.ProtocolDrug
import org.simple.clinic.reports.ReportsRepository
import org.simple.clinic.rules.LocalAuthenticationRule
import org.simple.clinic.user.User
import org.simple.clinic.user.UserSession
import org.simple.clinic.util.Just
import org.simple.clinic.util.None
import org.simple.clinic.util.Optional
import org.simple.clinic.util.Rules
import org.simple.clinic.util.TestUserClock
import org.simple.clinic.util.TestUtcClock
import org.simple.clinic.util.toOptional
import org.simple.clinic.util.unwrapJust
import org.threeten.bp.Duration
import org.threeten.bp.Instant
import org.threeten.bp.LocalDate
import org.threeten.bp.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

class PatientRepositoryAndroidTest {

  @Inject
  lateinit var patientRepository: PatientRepository

  @Inject
  lateinit var bloodPressureRepository: BloodPressureRepository

  @Inject
  lateinit var bloodSugarRepository: BloodSugarRepository

  @Inject
  lateinit var prescriptionRepository: PrescriptionRepository

  @Inject
  lateinit var appointmentRepository: AppointmentRepository

  @Inject
  lateinit var medicalHistoryRepository: MedicalHistoryRepository

  @Inject
  lateinit var reportsRepository: ReportsRepository

  @Inject
  lateinit var database: AppDatabase

  @Inject
  lateinit var facilityRepository: FacilityRepository

  @Inject
  lateinit var userSession: UserSession

  @Inject
  lateinit var testData: TestData

  @Inject
  lateinit var clock: TestUtcClock

  @Inject
  lateinit var userClock: TestUserClock

  @Inject
  lateinit var config: PatientConfig

  @Inject
  lateinit var businessIdMetaDataAdapter: BusinessIdMetaDataAdapter

  @Inject
  lateinit var loggedInUser: User

  @Inject
  lateinit var currentFacility: Facility

  @get:Rule
  val rules: RuleChain = Rules
      .global()
      .around(LocalAuthenticationRule())
      .around(InstantTaskExecutorRule())

  @Before
  fun setUp() {
    TestClinicApp.appComponent().inject(this)
    clock.setDate(LocalDate.parse("2018-01-01"))
    userClock.setDate(LocalDate.parse("2018-01-01"))
  }

  @After
  fun tearDown() {
    reportsRepository.deleteReportsFile().blockingGet()
  }

  @Test
  fun when_a_patient_with_phone_numbers_is_saved_then_it_should_be_correctly_stored_in_the_database() {
    val ongoingAddress = OngoingNewPatientEntry.Address(
        colonyOrVillage = "HSR Layout",
        district = "Bangalore South",
        state = "Karnataka",
        streetAddress = "Street Address",
        zone = "Zone"
    )
    val ongoingPersonalDetails = OngoingNewPatientEntry.PersonalDetails("Ashok Kumar", "08/04/1985", null, Gender.Transgender)
    val ongoingPhoneNumber = OngoingNewPatientEntry.PhoneNumber(number = "227788", type = PatientPhoneNumberType.Landline)

    val personalDetailsOnlyEntry = OngoingNewPatientEntry(personalDetails = ongoingPersonalDetails)

    val savedPatient = patientRepository.saveOngoingEntry(personalDetailsOnlyEntry)
        .andThen(patientRepository.ongoingEntry())
        .map { ongoingEntry -> ongoingEntry.copy(address = ongoingAddress) }
        .map { updatedEntry -> updatedEntry.copy(phoneNumber = ongoingPhoneNumber) }
        .flatMapCompletable { withAddressAndPhoneNumbers -> patientRepository.saveOngoingEntry(withAddressAndPhoneNumbers) }
        .andThen(patientRepository.saveOngoingEntryAsPatient(loggedInUser, currentFacility))
        .blockingGet()

    val patient = database.patientDao().getOne(savedPatient.uuid)!!

    assertThat(patient.dateOfBirth).isEqualTo(LocalDate.parse("1985-04-08"))
    assertThat(patient.age).isNull()

    val savedPhoneNumbers = database.phoneNumberDao().phoneNumber(patient.uuid).firstOrError().blockingGet()
    assertThat(savedPhoneNumbers).hasSize(1)
    assertThat(savedPhoneNumbers.first().number).isEqualTo("227788")
  }

  @Test
  fun when_a_patient_without_phone_numbers_is_saved_then_it_should_be_correctly_stored_in_the_database() {
    val patientEntry = testData.ongoingPatientEntry(fullName = "Jeevan Bima", phone = null)

    val savedPatient = patientRepository.saveOngoingEntry(patientEntry)
        .andThen(patientRepository.saveOngoingEntryAsPatient(loggedInUser, currentFacility))
        .blockingGet()

    val patient = database.patientDao().patient(savedPatient.uuid)

    assertThat(patient).isNotNull()

    val savedPhoneNumbers = database.phoneNumberDao().phoneNumber(savedPatient.uuid).firstOrError().blockingGet()
    assertThat(savedPhoneNumbers).isEmpty()
  }

  @Test
  fun when_a_patient_with_null_dateofbirth_and_nonnull_age_is_saved_then_it_should_be_correctly_stored_in_the_database() {
    val patientEntry = testData.ongoingPatientEntry(fullName = "Ashok Kumar")

    val savedPatient = patientRepository.saveOngoingEntry(patientEntry)
        .andThen(patientRepository.saveOngoingEntryAsPatient(loggedInUser, currentFacility))
        .blockingGet()

    val patient = database.patientDao().getOne(savedPatient.uuid)!!

    assertThat(patient.fullName).isEqualTo(patientEntry.personalDetails!!.fullName)
    assertThat(patient.dateOfBirth).isNull()
    assertThat(patient.age!!.value).isEqualTo(patientEntry.personalDetails!!.age!!.toInt())

    val savedPhoneNumbers = database.phoneNumberDao().phoneNumber(patient.uuid).firstOrError().blockingGet()

    assertThat(savedPhoneNumbers).hasSize(1)
    assertThat(savedPhoneNumbers.first().number).isEqualTo(patientEntry.phoneNumber!!.number)
  }

  @Test
  fun when_a_patient_with_null_dateofbirth_and_null_age_is_saved_then_it_should_not_be_accepted() {
    val patientEntry = testData.ongoingPatientEntry(dateOfBirth = null, age = null)

    patientRepository.saveOngoingEntry(patientEntry)
        .andThen(patientRepository.saveOngoingEntryAsPatient(loggedInUser, currentFacility))
        .test()
        .assertError(AssertionError::class.java)
  }

  @Test
  fun when_a_patient_with_an_identifier_is_saved_then_it_should_be_correctly_saved_in_the_database() {
    val identifier = testData.identifier(value = "id", type = BpPassport)
    val patientEntry = testData.ongoingPatientEntry(identifier = identifier)

    val now = Instant.now(clock)
    val advanceClockBy = Duration.ofDays(7L)
    clock.advanceBy(advanceClockBy)

    val savedPatientUuid = patientRepository.saveOngoingEntry(patientEntry)
        .andThen(patientRepository.saveOngoingEntryAsPatient(loggedInUser, currentFacility))
        .blockingGet()
        .uuid

    val patientProfile = database
        .patientDao()
        .patientProfileImmediate(savedPatientUuid)!!

    val savedBusinessId = patientProfile.businessIds.first()

    assertThat(savedBusinessId.identifier).isEqualTo(identifier)
    assertThat(savedBusinessId.createdAt).isEqualTo(now + advanceClockBy)
    assertThat(savedBusinessId.updatedAt).isEqualTo(now + advanceClockBy)
  }

  @Test
  fun when_a_patient_with_address_is_saved_then_search_should_correctly_return_combined_object() {
    val patientEntry = testData.ongoingPatientEntry(fullName = "Asha Kumar", dateOfBirth = "15/08/1947", age = null)

    patientRepository.saveOngoingEntry(patientEntry)
        .andThen(patientRepository.saveOngoingEntryAsPatient(loggedInUser, currentFacility))
        .subscribe()

    val combinedPatient = patientRepository.search(Name(patientName = "kumar"))
        .blockingFirst()
        .first()

    assertThat(combinedPatient.fullName).isEqualTo("Asha Kumar")
    assertThat(combinedPatient.gender).isEqualTo(patientEntry.personalDetails!!.gender)
    assertThat(combinedPatient.dateOfBirth).isEqualTo(LocalDate.parse("1947-08-15"))
    assertThat(combinedPatient.createdAt).isAtLeast(combinedPatient.address.createdAt)
    assertThat(combinedPatient.syncStatus).isEqualTo(PENDING)
    assertThat(combinedPatient.address.colonyOrVillage).isEqualTo(patientEntry.address!!.colonyOrVillage)
    assertThat(combinedPatient.address.state).isEqualTo(patientEntry.address!!.state)
    assertThat(combinedPatient.phoneNumber).isNotEmpty()
    assertThat(combinedPatient.phoneNumber).isEqualTo(patientEntry.phoneNumber!!.number)
    assertThat(combinedPatient.phoneActive).isEqualTo(patientEntry.phoneNumber!!.active)
  }

  @Test
  fun when_patients_are_present_then_search_should_correctly_find_them() {
    val ongoingPersonalDetails = OngoingNewPatientEntry.PersonalDetails("Abhay Kumar", "15/08/1950", null, Gender.Transgender)
    val ongoingAddress = OngoingNewPatientEntry.Address(
        colonyOrVillage = "Arambol",
        district = "Arambol",
        state = "Goa",
        streetAddress = "Street Address",
        zone = "Zone"
    )
    val ongoingPhoneNumber = OngoingNewPatientEntry.PhoneNumber("3914159", PatientPhoneNumberType.Mobile, active = true)
    val ongoingPatientEntry = OngoingNewPatientEntry(ongoingPersonalDetails, ongoingAddress, ongoingPhoneNumber)
    val abhayKumar = patientRepository.saveOngoingEntry(ongoingPatientEntry)
        .andThen(patientRepository.saveOngoingEntryAsPatient(loggedInUser, currentFacility))
        .blockingGet()

    val opd2 = OngoingNewPatientEntry.PersonalDetails("Alok Kumar", "15/08/1940", null, Gender.Transgender)
    val opa2 = OngoingNewPatientEntry.Address(
        colonyOrVillage = "Arambol",
        district = "Arambol",
        state = "Goa",
        streetAddress = "Street Address",
        zone = "Zone"
    )
    val opn2 = OngoingNewPatientEntry.PhoneNumber("3418959", PatientPhoneNumberType.Mobile, active = true)
    val ope2 = OngoingNewPatientEntry(opd2, opa2, opn2)
    patientRepository.saveOngoingEntry(ope2)
        .andThen(patientRepository.saveOngoingEntryAsPatient(loggedInUser, currentFacility))
        .blockingGet()

    val opd3 = OngoingNewPatientEntry.PersonalDetails("Abhishek Kumar", null, "68", Gender.Transgender)
    val opa3 = OngoingNewPatientEntry.Address(
        colonyOrVillage = "Arambol",
        district = "Arambol",
        state = "Goa",
        streetAddress = "Street Address",
        zone = "Zone"
    )
    val opn3 = OngoingNewPatientEntry.PhoneNumber("9989159", PatientPhoneNumberType.Mobile, active = true)
    val ope3 = OngoingNewPatientEntry(opd3, opa3, opn3)
    val abhishekKumar = patientRepository.saveOngoingEntry(ope3)
        .andThen(patientRepository.saveOngoingEntryAsPatient(loggedInUser, currentFacility))
        .blockingGet()

    val opd4 = OngoingNewPatientEntry.PersonalDetails("Abshot Kumar", null, "67", Gender.Transgender)
    val opa4 = OngoingNewPatientEntry.Address(
        colonyOrVillage = "Arambol",
        district = "Arambol",
        state = "Goa",
        streetAddress = "Street Address",
        zone = "Zone"
    )
    val opn4 = OngoingNewPatientEntry.PhoneNumber("1991591", PatientPhoneNumberType.Mobile, active = true)
    val ope4 = OngoingNewPatientEntry(opd4, opa4, opn4)
    val abshotKumar = patientRepository.saveOngoingEntry(ope4)
        .andThen(patientRepository.saveOngoingEntryAsPatient(loggedInUser, currentFacility))
        .blockingGet()

    val search0 = patientRepository.search(Name("Vinod")).blockingFirst()
    assertThat(search0).hasSize(0)

    val search1 = patientRepository.search(Name("Alok")).blockingFirst()
    val person1 = search1.first()
    assertThat(search1).hasSize(1)
    assertThat(person1.fullName).isEqualTo("Alok Kumar")
    assertThat(person1.dateOfBirth).isEqualTo(LocalDate.parse("1940-08-15"))
    assertThat(person1.phoneNumber).isEqualTo("3418959")

    val search2 = patientRepository.search(Name("ab")).blockingFirst()
    val expectedResultsInSearch2 = setOf(abhayKumar, abhishekKumar, abshotKumar)

    assertThat(search2).hasSize(expectedResultsInSearch2.size)
    search2.forEach { searchResult ->
      val expectedPatient = expectedResultsInSearch2.find { it.fullName == searchResult.fullName }!!

      assertThat(searchResult.fullName).isEqualTo(expectedPatient.fullName)
      assertThat(searchResult.dateOfBirth).isEqualTo(expectedPatient.dateOfBirth)
    }
  }

  @Test
  fun deleted_blood_pressures_should_be_excluded_when_searching_for_patients() {
    // given
    val now = Instant.now(clock)
    val currentFacility = facilityRepository.currentFacility(loggedInUser).blockingFirst()

    fun createPatientProfile(
        patientUuid: UUID,
        fullName: String
    ): PatientProfile {
      return testData.patientProfile(patientUuid = patientUuid)
          .let { profile ->
            profile.copy(patient = profile.patient.copy(
                fullName = fullName,
                status = Active))
          }
    }

    fun createBp(
        bpUuid: UUID,
        patientUuid: UUID,
        recordedAt: Instant,
        deletedAt: Instant? = null
    ): BloodPressureMeasurement {
      return testData.bloodPressureMeasurement(
          uuid = bpUuid,
          patientUuid = patientUuid,
          facilityUuid = currentFacility.uuid,
          userUuid = loggedInUser.uuid,
          createdAt = now,
          deletedAt = deletedAt,
          recordedAt = recordedAt)
    }

    val patientWithLatestBpDeleted = createPatientProfile(
        patientUuid = UUID.fromString("2e4f08a3-f224-4647-8010-709f1a696272"),
        fullName = "Patient with latest BP deleted"
    )
    val bpsForPatientWithLatestBpDeleted = listOf(
        createBp(UUID.fromString("b234458d-fa2c-4909-8891-f8c27999004b"), patientWithLatestBpDeleted.patientUuid, recordedAt = now.plusSeconds(2L)),
        createBp(UUID.fromString("eeeb56bd-a191-4dbb-8d0d-a811b246e856"), patientWithLatestBpDeleted.patientUuid, recordedAt = now),
        createBp(UUID.fromString("6e6be627-2b76-4acc-b932-342cd693663e"), patientWithLatestBpDeleted.patientUuid, recordedAt = now.plusSeconds(5L), deletedAt = now)
    )
    patientRepository.save(listOf(patientWithLatestBpDeleted))
        .andThen(bloodPressureRepository.save(bpsForPatientWithLatestBpDeleted))
        .blockingAwait()

    val patientWithOneDeletedBp = createPatientProfile(
        patientUuid = UUID.fromString("bc6116a5-4417-4f0e-869b-c01039aa1b67"),
        fullName = "Patient with only one deleted BP"
    )
    val bpsForPatientWithOneDeletedBp = listOf(
        createBp(UUID.fromString("08e5c49c-88f7-4612-9d79-0b994af80036"), patientWithOneDeletedBp.patientUuid, recordedAt = now, deletedAt = now)
    )
    patientRepository.save(listOf(patientWithOneDeletedBp))
        .andThen(bloodPressureRepository.save(bpsForPatientWithOneDeletedBp))
        .blockingAwait()

    val patientWithTwoDeletedBps = createPatientProfile(
        patientUuid = UUID.fromString("8d8419bf-205f-4ccf-8262-e29eb42ba437"),
        fullName = "Patient with two deleted BPs"
    )
    val bpsForPatientWithTwoDeletedBps = listOf(
        createBp(UUID.fromString("37c0ae26-f91e-4845-8c5b-3ac4550902f3"), patientWithTwoDeletedBps.patientUuid, recordedAt = now, deletedAt = now),
        createBp(UUID.fromString("d40861d5-bf3a-4418-a7e5-0f5bedcb416d"), patientWithTwoDeletedBps.patientUuid, recordedAt = now.plusSeconds(1L), deletedAt = now)
    )
    patientRepository.save(listOf(patientWithTwoDeletedBps))
        .andThen(bloodPressureRepository.save(bpsForPatientWithTwoDeletedBps))
        .blockingAwait()

    val patientWithNoBps = createPatientProfile(
        patientUuid = UUID.fromString("83b29eae-3600-420a-9289-7997571bf623"),
        fullName = "Patient with no BPs"
    )
    patientRepository.save(listOf(patientWithNoBps)).blockingAwait()

    // when
    val searchResults = patientRepository.search(Name("patient"))
        .blockingFirst()
        .associateBy { it.uuid }

    // then
    assertThat(searchResults.size).isEqualTo(4)
    val expectedLastSeenWithPatientWithLatestBpDeleted = LastSeen(
        lastSeenOn = now.plusSeconds(2L),
        lastSeenAtFacilityName = currentFacility.name,
        lastSeenAtFacilityUuid = currentFacility.uuid
    )
    assertThat(searchResults.getValue(patientWithLatestBpDeleted.patientUuid).lastSeen)
        .isEqualTo(expectedLastSeenWithPatientWithLatestBpDeleted)

    assertThat(searchResults.getValue(patientWithOneDeletedBp.patientUuid).lastSeen)
        .isNull()
    assertThat(searchResults.getValue(patientWithTwoDeletedBps.patientUuid).lastSeen)
        .isNull()
    assertThat(searchResults.getValue(patientWithNoBps.patientUuid).lastSeen)
        .isNull()
  }

  @Test
  fun when_the_patient_data_is_cleared_all_patient_data_must_be_cleared() {
    val facilityPayloads = listOf(testData.facilityPayload())
    val facilityUuid = facilityPayloads.first().uuid
    facilityRepository.mergeWithLocalData(facilityPayloads).blockingAwait()

    val user = testData.loggedInUser(
        uuid = UUID.fromString("763f291f-9995-4e42-b76c-a73fb8a79932"),
        registrationFacilityUuid = facilityUuid
    )

    with(database.userDao()) {
      createOrUpdate(user)
    }

    val patientPayloads = (1..2).map { testData.patientPayload() }

    val patientUuid = patientPayloads.first().uuid

    val rangeOfRecords = 1..4
    val bloodPressurePayloads = rangeOfRecords.map { testData.bpPayload(patientUuid = patientUuid, facilityUuid = facilityUuid) }
    val bloodSugarPayloads = rangeOfRecords.map { testData.bloodSugarPayload(patientUuid = patientUuid, facilityUuid = facilityUuid) }
    val prescriptionPayloads = rangeOfRecords.map { testData.prescriptionPayload(patientUuid = patientUuid, facilityUuid = facilityUuid) }
    val appointmentPayloads = rangeOfRecords.map { testData.appointmentPayload(patientUuid = patientUuid) }

    val appointmentUuid = appointmentPayloads.first().uuid
    val medicalHistoryPayloads = rangeOfRecords.map { testData.medicalHistoryPayload(patientUuid = patientUuid) }

    Completable.mergeArray(
        patientRepository.mergeWithLocalData(patientPayloads),
        bloodPressureRepository.mergeWithLocalData(bloodPressurePayloads),
        bloodSugarRepository.mergeWithLocalData(bloodSugarPayloads),
        prescriptionRepository.mergeWithLocalData(prescriptionPayloads),
        appointmentRepository.mergeWithLocalData(appointmentPayloads),
        medicalHistoryRepository.mergeWithLocalData(medicalHistoryPayloads)
    ).blockingAwait()

    reportsRepository.updateReports("test reports!").blockingAwait()
    val (reportsFile) = reportsRepository.reportsFile().blockingFirst()

    // We need to ensure that ONLY the tables related to the patient get cleared,
    // and the ones referring to the user must be left untouched

    assertThat(database.patientDao().patientCount().blockingFirst()).isGreaterThan(0)
    assertThat(database.addressDao().count()).isGreaterThan(0)
    assertThat(database.phoneNumberDao().count()).isGreaterThan(0)
    assertThat(database.businessIdDao().count()).isGreaterThan(0)
    assertThat(database.bloodPressureDao().count().blockingFirst()).isGreaterThan(0)
    assertThat(database.bloodSugarDao().count().blockingFirst()).isGreaterThan(0)
    assertThat(database.prescriptionDao().count().blockingFirst()).isGreaterThan(0)
    assertThat(database.facilityDao().count().blockingFirst()).isGreaterThan(0)
    assertThat(database.userDao().userImmediate()).isNotNull()
    assertThat(database.appointmentDao().count().blockingFirst()).isGreaterThan(0)
    assertThat(database.medicalHistoryDao().count().blockingFirst()).isGreaterThan(0)
    assertThat(reportsFile!!.exists()).isTrue()

    patientRepository.clearPatientData().blockingAwait()

    assertThat(database.patientDao().patientCount().blockingFirst()).isEqualTo(0)
    assertThat(database.addressDao().count()).isEqualTo(0)
    assertThat(database.phoneNumberDao().count()).isEqualTo(0)
    assertThat(database.businessIdDao().count()).isEqualTo(0)
    assertThat(database.bloodPressureDao().count().blockingFirst()).isEqualTo(0)
    assertThat(database.bloodSugarDao().count().blockingFirst()).isEqualTo(0)
    assertThat(database.prescriptionDao().count().blockingFirst()).isEqualTo(0)
    assertThat(database.appointmentDao().count().blockingFirst()).isEqualTo(0)
    assertThat(database.medicalHistoryDao().count().blockingFirst()).isEqualTo(0)
    assertThat(reportsFile.exists()).isFalse()

    assertThat(database.facilityDao().count().blockingFirst()).isGreaterThan(0)
    assertThat(database.userDao().userImmediate()).isNotNull()
  }

  @Test
  fun when_patient_is_marked_dead_they_should_not_show_in_search_results() {
    val patient = patientRepository
        .saveOngoingEntry(testData.ongoingPatientEntry(fullName = "Ashok Kumar"))
        .andThen(patientRepository.saveOngoingEntryAsPatient(loggedInUser, currentFacility))
        .blockingGet()

    val searchResults = patientRepository.search(Name(patientName = "Ashok")).blockingFirst()
    assertThat(searchResults).isNotEmpty()
    assertThat(searchResults.first().fullName).isEqualTo("Ashok Kumar")

    patientRepository.updatePatientStatusToDead(patient.uuid)

    val searchResultsAfterUpdate = patientRepository.search(Name(patientName = "Ashok")).blockingFirst()
    assertThat(patientRepository.recordCount().blockingFirst()).isEqualTo(1)
    assertThat(searchResultsAfterUpdate).isEmpty()

    val deadPatient: Patient = patientRepository.patient(patient.uuid)
        .unwrapJust()
        .blockingFirst()

    assertThat(patientRepository.recordCount().blockingFirst()).isEqualTo(1)
    assertThat(deadPatient.status).isEqualTo(Dead)
  }

  @Test
  fun when_patient_is_marked_dead_they_should_marked_as_pending_sync() {
    val timeOfCreation = Instant.now(clock)

    val patient = patientRepository
        .saveOngoingEntry(testData.ongoingPatientEntry(fullName = "Ashok Kumar"))
        .andThen(patientRepository.saveOngoingEntryAsPatient(loggedInUser, currentFacility))
        .blockingGet()

    clock.advanceBy(Duration.ofDays(365))
    val timeOfDeath = Instant.now(clock)

    patientRepository.updatePatientStatusToDead(patient.uuid)
    val deadPatient: Patient = patientRepository.patient(patient.uuid)
        .unwrapJust()
        .blockingFirst()

    assertThat(deadPatient.syncStatus).isEqualTo(PENDING)
    assertThat(deadPatient.updatedAt).isNotEqualTo(timeOfCreation)
    assertThat(deadPatient.updatedAt).isEqualTo(timeOfDeath)
  }

  /**
   * Added to test the case where SQLite's max query param length (999) can be
   * exceeded during fuzzy name search.
   */
  @Test
  fun when_searching_with_fuzzy_search_the_results_must_be_limited_to_the_value_set_in_the_config() {
    val template = testData.patientProfile(syncStatus = DONE, patientName = "Name")

    val patientsToSave = (1..PatientConfig.MAXIMUM_SQLITE_QUERY_LIMIT).map {
      val addressUuid = UUID.randomUUID()
      val patientUuid = UUID.randomUUID()

      template.copy(
          patient = template.patient.copy(
              uuid = patientUuid,
              addressUuid = addressUuid,
              dateOfBirth = LocalDate.now(clock).minusYears(10),
              status = Active
          ),
          address = template.address.copy(uuid = addressUuid),
          phoneNumbers = template.phoneNumbers
              .map { number -> number.copy(uuid = UUID.randomUUID(), patientUuid = patientUuid) },
          businessIds = template.businessIds
              .map { businessId -> businessId.copy(uuid = UUID.randomUUID(), patientUuid = patientUuid) }
      )
    }

    patientRepository.save(patientsToSave).blockingAwait()
    assertThat(patientRepository.recordCount().blockingFirst()).isEqualTo(1000)

    assertThat(
        patientRepository
            .search(Name(patientName = "ame"))
            .blockingFirst()
            .size
    ).isEqualTo(config.limitOfSearchResults)
  }

  /**
   * Added to test the case where SQLite's max query param length (999) can be
   * exceeded during phone number search.
   */
  @Test
  fun when_searching_with_phone_number_the_results_must_be_limited_to_the_value_set_in_the_config() {
    val template = testData.patientProfile(
        syncStatus = DONE,
        patientPhoneNumber = "1234567890"
    )

    val patientsToSave = (1..PatientConfig.MAXIMUM_SQLITE_QUERY_LIMIT).map {
      val addressUuid = UUID.randomUUID()
      val patientUuid = UUID.randomUUID()

      template.copy(
          patient = template.patient.copy(
              uuid = patientUuid,
              addressUuid = addressUuid,
              dateOfBirth = LocalDate.now(clock).minusYears(10),
              status = Active
          ),
          address = template.address.copy(uuid = addressUuid),
          phoneNumbers = template.phoneNumbers
              .map { number -> number.copy(uuid = UUID.randomUUID(), patientUuid = patientUuid) },
          businessIds = template.businessIds
              .map { businessId -> businessId.copy(uuid = UUID.randomUUID(), patientUuid = patientUuid) }
      )
    }

    patientRepository.save(patientsToSave).blockingAwait()
    assertThat(patientRepository.recordCount().blockingFirst()).isEqualTo(1000)

    assertThat(
        patientRepository
            .search(PhoneNumber("1234"))
            .blockingFirst()
            .size
    ).isEqualTo(config.limitOfSearchResults)
  }

  @Test
  fun editing_a_patients_phone_number_should_not_trigger_foreign_key_cascades_action() {
    val patientUuid = UUID.randomUUID()
    val initialNumber = testData.phoneNumberPayload(number = "123")
    val initialPatient = testData.patientPayload(uuid = patientUuid, phoneNumbers = listOf(initialNumber))
    patientRepository.mergeWithLocalData(listOf(initialPatient)).blockingAwait()

    val updatedNumber = initialNumber.copy(number = "456")
    val updatedPatient = initialPatient.copy(phoneNumbers = listOf(updatedNumber))
    patientRepository.mergeWithLocalData(listOf(updatedPatient)).blockingAwait()

    assertThat(database.phoneNumberDao().count()).isEqualTo(1)

    database.patientDao().save(updatedPatient.toDatabaseModel(DONE))

    val storedNumbers = database.phoneNumberDao().phoneNumber(patientUuid).blockingFirst()
    assertThat(storedNumbers.size).isEqualTo(1)
    assertThat(storedNumbers[0].uuid).isEqualTo(updatedNumber.uuid)
    assertThat(storedNumbers[0].number).isEqualTo(updatedNumber.number)

    assertThat(database.openHelper.writableDatabase.isDatabaseIntegrityOk).isTrue()
  }

  @Test
  fun editing_a_patients_address_should_not_trigger_foreign_key_cascades_action() {
    val patientUuid = UUID.randomUUID()
    val initialAddress = testData.addressPayload(district = "Gotham")
    val initialPatient = testData.patientPayload(uuid = patientUuid, address = initialAddress)
    patientRepository.mergeWithLocalData(listOf(initialPatient)).blockingAwait()

    assertThat(database.addressDao().count()).isEqualTo(1)
    assertThat(database.patientDao().patientCount().blockingFirst()).isEqualTo(1)

    val updatedAddress = initialAddress.copy(district = "Death Star")
    database.addressDao().save(updatedAddress.toDatabaseModel())

    assertThat(database.patientDao().patientCount().blockingFirst()).isEqualTo(1)

    val storedPatients = database.patientDao().patient(initialPatient.uuid).blockingFirst()
    assertThat(storedPatients.size).isEqualTo(1)
    assertThat(storedPatients[0].uuid).isEqualTo(patientUuid)

    assertThat(database.openHelper.writableDatabase.isDatabaseIntegrityOk).isTrue()
  }

  @Test
  fun editing_a_patients_profile_should_not_trigger_foreign_key_cascades_action() {
    val patientUuid = UUID.randomUUID()
    val address = testData.addressPayload()
    val number = testData.phoneNumberPayload()
    val initialPatient = testData.patientPayload(
        uuid = patientUuid,
        fullName = "Scarecrow",
        address = address,
        phoneNumbers = listOf(number))
    patientRepository.mergeWithLocalData(listOf(initialPatient)).blockingAwait()

    assertThat(database.phoneNumberDao().count()).isEqualTo(1)
    assertThat(database.addressDao().count()).isEqualTo(1)

    val updatedPatient = initialPatient.copy()
    database.patientDao().save(updatedPatient.toDatabaseModel(DONE))

    val storedNumbers = database.phoneNumberDao().phoneNumber(patientUuid).blockingFirst()
    assertThat(database.phoneNumberDao().count()).isEqualTo(1)
    assertThat(storedNumbers.size).isEqualTo(1)
    assertThat(storedNumbers[0].uuid).isEqualTo(number.uuid)

    val storedAddresses = database.addressDao().address(address.uuid).blockingFirst()
    assertThat(database.addressDao().count()).isEqualTo(1)
    assertThat(storedAddresses.size).isEqualTo(1)
    assertThat(storedAddresses[0].uuid).isEqualTo(address.uuid)
  }

  @Test
  fun when_patient_address_is_updated_the_address_must_be_saved() {
    val addressToSave = testData.patientAddress(
        colonyOrVillage = "Old Colony",
        district = "Old District",
        state = "Old State",
        createdAt = Instant.now(clock),
        updatedAt = Instant.now(clock)
    )

    val patientProfile = PatientProfile(
        patient = testData.patient(
            addressUuid = addressToSave.uuid,
            syncStatus = DONE
        ),
        address = addressToSave,
        phoneNumbers = emptyList(),
        businessIds = emptyList()
    )

    val patient = patientProfile.patient

    patientRepository.save(listOf(patientProfile)).blockingAwait()

    val updatedAfter = Duration.ofDays(1L)
    clock.advanceBy(updatedAfter)

    val oldSavedAddress = patientRepository.address(patient.addressUuid)
        .unwrapJust()
        .blockingFirst()

    val newAddressToSave = oldSavedAddress.copy(
        colonyOrVillage = "New Colony",
        district = "New District",
        state = "New State"
    )

    patientRepository.updateAddressForPatient(patientUuid = patient.uuid, patientAddress = newAddressToSave).blockingAwait()

    val updatedPatient = patientRepository.patient(patient.uuid)
        .unwrapJust()
        .blockingFirst()

    assertThat(updatedPatient.syncStatus).isEqualTo(PENDING)

    val savedAddress = patientRepository.address(updatedPatient.addressUuid)
        .unwrapJust()
        .blockingFirst()

    assertThat(savedAddress.updatedAt).isEqualTo(oldSavedAddress.updatedAt.plus(updatedAfter))
    assertThat(savedAddress.createdAt).isNotEqualTo(savedAddress.updatedAt)
    assertThat(savedAddress.colonyOrVillage).isEqualTo("New Colony")
    assertThat(savedAddress.district).isEqualTo("New District")
    assertThat(savedAddress.state).isEqualTo("New State")
  }

  @Test
  fun when_patient_is_updated_the_patient_must_be_saved() {
    val addressToSave = testData.patientAddress(
        colonyOrVillage = "Old Colony",
        district = "Old District",
        state = "Old State"
    )

    val originalSavedPatient = testData.patient(
        syncStatus = DONE,
        addressUuid = addressToSave.uuid,
        fullName = "Old Name",
        gender = Gender.Male,
        age = Age(30, Instant.now(clock)),
        dateOfBirth = LocalDate.now(clock),
        createdAt = Instant.now(clock),
        updatedAt = Instant.now(clock)
    )

    val patientProfile = PatientProfile(
        patient = originalSavedPatient,
        address = addressToSave,
        phoneNumbers = listOf(
            testData.patientPhoneNumber(patientUuid = originalSavedPatient.uuid),
            testData.patientPhoneNumber(patientUuid = originalSavedPatient.uuid)
        ),
        businessIds = emptyList()
    )

    patientRepository.save(listOf(patientProfile)).blockingAwait()

    val updatedAfter = Duration.ofDays(1L)
    clock.advanceBy(updatedAfter)

    val newPatientToSave = originalSavedPatient.copy(
        fullName = "New Name",
        gender = Gender.Transgender,
        age = Age(35, Instant.now(clock)),
        dateOfBirth = LocalDate.now(clock)
    )

    patientRepository.updatePatient(newPatientToSave).blockingAwait()

    val savedPatient = patientRepository.patient(newPatientToSave.uuid)
        .unwrapJust()
        .blockingFirst()

    assertThat(savedPatient.syncStatus).isEqualTo(PENDING)
    assertThat(savedPatient.updatedAt).isEqualTo(originalSavedPatient.updatedAt.plus(updatedAfter))
    assertThat(savedPatient.createdAt).isNotEqualTo(savedPatient.updatedAt)

    assertThat(savedPatient.fullName).isEqualTo("New Name")
    assertThat(savedPatient.gender).isEqualTo(Gender.Transgender)
  }

  @Test
  fun when_phone_number_is_updated_it_should_be_saved() {
    val addressToSave = testData.patientAddress()

    val originalSavedPatient = testData.patient(
        syncStatus = DONE,
        addressUuid = addressToSave.uuid
    )

    val patientProfile = PatientProfile(
        patient = originalSavedPatient,
        address = addressToSave,
        phoneNumbers = listOf(
            testData.patientPhoneNumber(
                patientUuid = originalSavedPatient.uuid,
                number = "111111111",
                phoneType = PatientPhoneNumberType.Landline,
                createdAt = Instant.now(clock),
                updatedAt = Instant.now(clock)
            ),
            testData.patientPhoneNumber(
                patientUuid = originalSavedPatient.uuid,
                number = "2222222222",
                phoneType = PatientPhoneNumberType.Mobile,
                createdAt = Instant.now(clock),
                updatedAt = Instant.now(clock)
            )
        ),
        businessIds = emptyList()
    )

    patientRepository.save(listOf(patientProfile))
        .blockingAwait()

    val updatedAfter = Duration.ofDays(1L)
    clock.advanceBy(updatedAfter)

    val phoneNumberToUpdate = patientProfile.phoneNumbers[1].copy(number = "12345678", phoneType = PatientPhoneNumberType.Landline)

    patientRepository.updatePhoneNumberForPatient(originalSavedPatient.uuid, phoneNumberToUpdate).blockingAwait()

    val phoneNumbersSaved = database.phoneNumberDao()
        .phoneNumber(originalSavedPatient.uuid)
        .firstOrError()
        .blockingGet()

    val phoneNumber = phoneNumbersSaved.find { it.uuid == phoneNumberToUpdate.uuid }

    assertThat(phoneNumber).isNotNull()
    assertThat(phoneNumber!!.number).isEqualTo("12345678")
    assertThat(phoneNumber.phoneType).isEqualTo(PatientPhoneNumberType.Landline)
    assertThat(phoneNumber.updatedAt).isEqualTo(patientProfile.phoneNumbers[1].updatedAt.plus(updatedAfter))
    assertThat(phoneNumber.updatedAt).isNotEqualTo(phoneNumber.createdAt)

    val patient = patientRepository.patient(originalSavedPatient.uuid)
        .unwrapJust()
        .blockingFirst()

    assertThat(patient.syncStatus).isEqualTo(PENDING)
  }

  @Test
  fun phone_number_should_be_saved_properly() {
    val addressToSave = testData.patientAddress()

    val originalSavedPatient = testData.patient(
        syncStatus = DONE,
        addressUuid = addressToSave.uuid
    )

    val patientProfile = PatientProfile(
        patient = originalSavedPatient,
        address = addressToSave,
        phoneNumbers = listOf(
            testData.patientPhoneNumber(
                patientUuid = originalSavedPatient.uuid,
                number = "111111111",
                phoneType = PatientPhoneNumberType.Landline,
                createdAt = Instant.now(clock),
                updatedAt = Instant.now(clock))),
        businessIds = emptyList())

    patientRepository.save(listOf(patientProfile))
        .blockingAwait()

    val updatedAfter = Duration.ofDays(1L)
    clock.advanceBy(updatedAfter)

    patientRepository.createPhoneNumberForPatient(
        patientUuid = originalSavedPatient.uuid,
        number = "2222222222",
        phoneNumberType = PatientPhoneNumberType.Mobile,
        active = true
    ).blockingAwait()

    val phoneNumbersSaved = database.phoneNumberDao()
        .phoneNumber(originalSavedPatient.uuid)
        .firstOrError()
        .blockingGet()

    assertThat(phoneNumbersSaved.size).isEqualTo(2)

    val savedPhoneNumber = phoneNumbersSaved.find { it != patientProfile.phoneNumbers[0] }!!

    assertThat(savedPhoneNumber.active).isTrue()
    assertThat(savedPhoneNumber.createdAt).isEqualTo(Instant.now(clock))
    assertThat(savedPhoneNumber.createdAt).isEqualTo(savedPhoneNumber.updatedAt)
    assertThat(savedPhoneNumber.number).isEqualTo("2222222222")
    assertThat(savedPhoneNumber.phoneType).isEqualTo(PatientPhoneNumberType.Mobile)

    val patient = patientRepository.patient(originalSavedPatient.uuid)
        .unwrapJust()
        .blockingFirst()

    assertThat(patient.syncStatus).isEqualTo(PENDING)
  }

  @Test
  fun verify_recent_patients_are_retrieved_as_expected() {
    var recentPatient1 = savePatientWithBpWithTestClock()

    verifyRecentPatientOrder(
        recentPatient1
    )

    clock.advanceBy(Duration.ofSeconds(1))

    var recentPatient2 = savePatientWithBpWithTestClock()

    verifyRecentPatientOrder(
        recentPatient2,
        recentPatient1
    )

    clock.advanceBy(Duration.ofSeconds(1))

    prescriptionRepository.savePrescription(
        uuid = UUID.fromString("f5533aa1-e4ea-4564-9e19-02428dd68eb9"),
        patientUuid = recentPatient1.uuid,
        drug = testData.protocolDrug(),
        facility = currentFacility
    ).blockingAwait()

    recentPatient1 = recentPatient1.copy(updatedAt = clock.instant())
    verifyRecentPatientOrder(
        recentPatient1,
        recentPatient2
    )

    clock.advanceBy(Duration.ofSeconds(1))

    val appointment2 = testData.appointment(
        patientUuid = recentPatient2.uuid,
        facilityUuid = currentFacility.uuid,
        creationFacilityUuid = currentFacility.uuid,
        createdAt = clock.instant(),
        updatedAt = clock.instant().plusSeconds(1),
        status = Scheduled,
        appointmentType = Manual,
        cancelReason = null
    )
    appointmentRepository.save(listOf(appointment2)).blockingAwait()

    recentPatient2 = recentPatient2.copy(updatedAt = clock.instant())
    verifyRecentPatientOrder(
        recentPatient2,
        recentPatient1
    )

    clock.advanceBy(Duration.ofSeconds(1))
    val instant = Instant.now(clock)

    medicalHistoryRepository
        .save(
            history = testData.medicalHistory(
                patientUuid = recentPatient1.uuid,
                updatedAt = instant
            ),
            updateTime = instant
        )

    verifyRecentPatientOrder(
        recentPatient2,
        recentPatient1
    )

    clock.advanceBy(Duration.ofSeconds(1))

    val recentPatient3 = savePatientWithBpWithTestClock()

    verifyRecentPatientOrder(
        recentPatient3,
        recentPatient2,
        recentPatient1
    )

    clock.advanceBy(Duration.ofSeconds(1))

    val recentPatient4 = savePatientWithBpWithTestClock()

    verifyRecentPatientOrder(
        recentPatient4,
        recentPatient3,
        recentPatient2
    )

    clock.advanceBy(Duration.ofSeconds(1))

    val recentPatient5 = savePatientWithBloodSugarWithTestClock()

    verifyRecentPatientOrder(
        recentPatient5,
        recentPatient4,
        recentPatient3
    )

    clock.advanceBy(Duration.ofSeconds(1))

    val recentPatient6Uuid = UUID.fromString("1b29c239-2398-425c-aa19-7693e4bf846e")
    savePatientWithBloodSugarWithTestClock(patientUuid = recentPatient6Uuid)
    val recentPatient6WithBP = savePatientWithBpWithTestClock(patientUuid = recentPatient6Uuid)

    verifyRecentPatientOrder(
        recentPatient6WithBP,
        recentPatient5,
        recentPatient4
    )
  }

  @Test
  fun verify_recent_patients_from_other_facilities_are_not_retrieved() {
    val facility1Uuid = UUID.randomUUID()
    val patient1InFacility1 = savePatientWithBp(facilityUuid = facility1Uuid)

    verifyRecentPatientOrder(patient1InFacility1, facilityUuid = facility1Uuid)

    val facility2Uuid = UUID.randomUUID()
    val patient1InFacility2 = savePatientWithBp(facilityUuid = facility2Uuid)

    verifyRecentPatientOrder(patient1InFacility1, facilityUuid = facility1Uuid)
    verifyRecentPatientOrder(patient1InFacility2, facilityUuid = facility2Uuid)

    val patient2InFacility1 = savePatientWithBp(facilityUuid = facility1Uuid)

    verifyRecentPatientOrder(patient2InFacility1, patient1InFacility1, facilityUuid = facility1Uuid)
  }

  @Test
  fun verify_deleted_bps_are_not_included_when_fetching_recent_patients() {
    val facilityUuid = currentFacility.uuid
    val recentPatient1 = savePatientWithBp(facilityUuid = facilityUuid)
    savePatientWithBp(facilityUuid = facilityUuid, deletedAt = Instant.now())
    val recentPatient3 = savePatientWithBp(facilityUuid = facilityUuid)

    val recentPatients = patientRepository
        .recentPatients(facilityUuid)
        .blockingFirst()
    assertThat(recentPatients).isEqualTo(listOf(recentPatient3, recentPatient1))
  }

  private fun savePatientWithBp(
      facilityUuid: UUID = currentFacility.uuid,
      patientUuid: UUID = UUID.randomUUID(),
      patientAddressUuid: UUID = UUID.randomUUID(),
      createdAt: Instant = Instant.now(),
      updatedAt: Instant = Instant.now(),
      deletedAt: Instant? = null,
      recordedAt: Instant = Instant.now(),
      patientStatus: PatientStatus = Active
  ): RecentPatient {
    val patientProfile = testData.patientProfile(patientUuid = patientUuid, patientAddressUuid = patientAddressUuid).run {
      copy(patient = patient.copy(status = patientStatus))
    }
    patientRepository.save(listOf(patientProfile)).blockingAwait()

    val bpMeasurement = testData.bloodPressureMeasurement(
        patientUuid = patientUuid,
        facilityUuid = facilityUuid,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        recordedAt = recordedAt
    )
    database.bloodPressureDao().save(listOf(bpMeasurement))
    return patientProfile.patient.toRecentPatient(bpMeasurement.recordedAt)
  }

  private fun savePatientWithBpWithTestClock(
      facilityUuid: UUID = currentFacility.uuid,
      patientUuid: UUID = UUID.randomUUID(),
      createdAt: Instant = Instant.now(clock),
      updatedAt: Instant = Instant.now(clock),
      deletedAt: Instant? = null,
      recordedAt: Instant = Instant.now(clock)
  ): RecentPatient {
    val patientProfile = testData.patientProfile(patientUuid = patientUuid).run {
      copy(patient = patient.copy(createdAt = createdAt, updatedAt = updatedAt))
    }
    patientRepository.save(listOf(patientProfile)).blockingAwait()

    val bpMeasurement = testData.bloodPressureMeasurement(
        patientUuid = patientUuid,
        facilityUuid = facilityUuid,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        recordedAt = recordedAt
    )
    database.bloodPressureDao().save(listOf(bpMeasurement))
    return patientProfile.patient.toRecentPatient(bpMeasurement.recordedAt)
  }

  private fun savePatientWithBloodSugarWithTestClock(
      facilityUuid: UUID = currentFacility.uuid,
      patientUuid: UUID = UUID.randomUUID(),
      createdAt: Instant = Instant.now(clock),
      updatedAt: Instant = Instant.now(clock),
      deletedAt: Instant? = null,
      recordedAt: Instant = Instant.now(clock)
  ): RecentPatient {
    val patientProfile = testData.patientProfile(patientUuid = patientUuid).run {
      copy(patient = patient.copy(createdAt = createdAt, updatedAt = updatedAt))
    }
    patientRepository.save(listOf(patientProfile)).blockingAwait()

    val bloodSugarMeasurement = testData.bloodSugarMeasurement(
        patientUuid = patientUuid,
        facilityUuid = facilityUuid,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        recordedAt = recordedAt
    )
    database.bloodSugarDao().save(listOf(bloodSugarMeasurement))
    return patientProfile.patient.toRecentPatient(bloodSugarMeasurement.recordedAt)
  }

  private fun Patient.toRecentPatient(recordedAt: Instant) = RecentPatient(
      uuid = uuid,
      fullName = fullName,
      gender = gender,
      dateOfBirth = dateOfBirth,
      age = age,
      updatedAt = recordedAt
  )

  private fun verifyRecentPatientOrder(
      vararg expectedRecentPatients: RecentPatient,
      facilityUuid: UUID = currentFacility.uuid
  ) {
    val recentPatients = patientRepository
        .recentPatients(facilityUuid, limit = 3)
        .blockingFirst()
    assertThat(recentPatients).isEqualTo(expectedRecentPatients.toList())
  }

  @Test
  fun verify_deleted_prescribed_drugs_are_not_included_when_fetching_recent_patients() {
    val facilityUuid = currentFacility.uuid
    val recentPatient1 = savePatientWithPrescribedDrug(facilityUuid = facilityUuid)
    val recentPatient2 = savePatientWithPrescribedDrug(facilityUuid = facilityUuid, deletedAt = Instant.now())
    val recentPatient3 = savePatientWithPrescribedDrug(facilityUuid = facilityUuid)

    val recentPatients = patientRepository
        .recentPatients(facilityUuid)
        .blockingFirst()
    assertThat(recentPatients).isEqualTo(listOf(recentPatient3, recentPatient1))
  }

  private fun savePatientWithPrescribedDrug(
      facilityUuid: UUID = currentFacility.uuid,
      patientUuid: UUID = UUID.randomUUID(),
      createdAt: Instant = Instant.now(),
      updatedAt: Instant = Instant.now(),
      deletedAt: Instant? = null
  ): RecentPatient {
    val patientProfile = testData.patientProfile(patientUuid = patientUuid)
    patientRepository.save(listOf(patientProfile)).blockingAwait()

    val prescribedDrug = testData.prescription(
        patientUuid = patientUuid,
        facilityUuid = facilityUuid,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt
    )
    database.prescriptionDao().save(listOf(prescribedDrug))
    return patientProfile.patient.run {
      RecentPatient(
          uuid = uuid,
          fullName = fullName,
          gender = gender,
          dateOfBirth = dateOfBirth,
          age = age,
          updatedAt = updatedAt
      )
    }
  }

  @Test
  fun verify_deleted_appointments_are_not_included_when_fetching_recent_patients() {
    val facilityUuid = UUID.fromString("086a5afd-c14b-4023-8eaa-9f29eee180a5")
    val recentPatient1 = savePatientWithAppointment(facilityUuid = facilityUuid, creationFacilityUuid = facilityUuid)
    val recentPatient2 = savePatientWithAppointment(facilityUuid = facilityUuid, creationFacilityUuid = facilityUuid, deletedAt = Instant.now())
    val recentPatient3 = savePatientWithAppointment(facilityUuid = facilityUuid, creationFacilityUuid = facilityUuid)

    val recentPatients = patientRepository
        .recentPatients(facilityUuid)
        .blockingFirst()
    assertThat(recentPatients).isEqualTo(listOf(recentPatient3, recentPatient1))
  }

  @Test
  fun verify_only_scheduled_appointments_are_included_when_fetching_recent_patients() {
    val facilityUuid = UUID.fromString("7d314030-97a2-4075-a0f7-15363fe17070")
    val recentPatient1 = savePatientWithAppointment(
        facilityUuid = facilityUuid,
        creationFacilityUuid = facilityUuid,
        status = Scheduled
    )
    val recentPatient2 = savePatientWithAppointment(
        facilityUuid = facilityUuid,
        creationFacilityUuid = facilityUuid,
        status = Cancelled
    )
    val recentPatient3 = savePatientWithAppointment(
        facilityUuid = facilityUuid,
        creationFacilityUuid = facilityUuid,
        status = Visited
    )

    val recentPatients = patientRepository
        .recentPatients(facilityUuid)
        .blockingFirst()
    assertThat(recentPatients).isEqualTo(listOf(recentPatient1))
  }

  @Test
  fun verify_only_appointments_with_correct_facility_ID_are_included_when_fetching_recent_patients() {
    val fromFacilityUuid = UUID.fromString("25fc9220-3fcb-43b4-8f47-fb67ad275094")
    val toFacilityUuid = UUID.fromString("7d314030-97a2-4075-a0f7-15363fe17070")
    val recentPatient = savePatientWithAppointment(
        facilityUuid = toFacilityUuid,
        creationFacilityUuid = fromFacilityUuid,
        status = Scheduled
    )

    val recentPatientsInFromFacility = patientRepository.recentPatients(fromFacilityUuid).blockingFirst()
    assertThat(recentPatientsInFromFacility).isEqualTo(listOf(recentPatient))

    val recentPatientsInToFacility = patientRepository.recentPatients(toFacilityUuid).blockingFirst()
    assertThat(recentPatientsInToFacility).isEqualTo(emptyList<RecentPatient>())
  }

  @Test
  fun verify_only_patients_with_manual_appointments_are_included_when_fetching_recent_patients() {
    val facilityUuid = UUID.fromString("e86e036c-8a55-4a07-8678-32f13b006dcb")
    val recentPatient1 = savePatientWithAppointment(facilityUuid = facilityUuid, creationFacilityUuid = facilityUuid, appointmentType = Automatic)
    val recentPatient2 = savePatientWithAppointment(facilityUuid = facilityUuid, creationFacilityUuid = facilityUuid, appointmentType = Manual)
    val recentPatient3 = savePatientWithAppointment(facilityUuid = facilityUuid, creationFacilityUuid = facilityUuid,
        appointmentType = AppointmentType.Unknown(""))

    val recentPatients = patientRepository
        .recentPatients(facilityUuid)
        .blockingFirst()
    assertThat(recentPatients).isEqualTo(listOf(recentPatient2))
  }

  private fun savePatientWithAppointment(
      appointmentUuid: UUID = UUID.randomUUID(),
      facilityUuid: UUID = currentFacility.uuid,
      creationFacilityUuid: UUID = currentFacility.uuid,
      patientUuid: UUID = UUID.randomUUID(),
      createdAt: Instant = Instant.now(),
      updatedAt: Instant = Instant.now(),
      deletedAt: Instant? = null,
      status: Status = Scheduled,
      appointmentType: AppointmentType = Manual
  ): RecentPatient {
    val patientProfile = testData.patientProfile(patientUuid = patientUuid)
    patientRepository.save(listOf(patientProfile)).blockingAwait()

    val appointment = testData.appointment(
        uuid = appointmentUuid,
        patientUuid = patientUuid,
        facilityUuid = facilityUuid,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        status = status,
        appointmentType = appointmentType,
        creationFacilityUuid = creationFacilityUuid
    )
    database.appointmentDao().save(listOf(appointment))
    return patientProfile.patient.run {
      RecentPatient(
          uuid = uuid,
          fullName = fullName,
          gender = gender,
          dateOfBirth = dateOfBirth,
          age = age,
          updatedAt = createdAt
      )
    }
  }

  @Test
  fun if_sync_is_pending_for_any_patient_record_then_it_should_be_counted_in_pendingRecordsCount() {
    patientRepository.save(listOf(
        testData.patientProfile(syncStatus = PENDING),
        testData.patientProfile(syncStatus = PENDING),
        testData.patientProfile(syncStatus = DONE)
    )).blockingAwait()

    val count = patientRepository.pendingSyncRecordCount().blockingFirst()
    assertThat(count).isEqualTo(2)
  }

  @Test
  fun saving_a_bp_identifier_for_a_patient_must_work_as_expected() {
    val patientProfile = testData.patientProfile(syncStatus = DONE, generateBusinessId = false)
    patientRepository.save(listOf(patientProfile)).blockingAwait()

    val bpPassportCode = UUID.randomUUID().toString()
    val now = Instant.now(clock)

    val duration = Duration.ofDays(1L)
    clock.advanceBy(duration)

    val savedBusinessId = patientRepository
        .addIdentifierToPatient(
            patientUuid = patientProfile.patient.uuid,
            identifier = Identifier(bpPassportCode, BpPassport),
            assigningUser = loggedInUser,
            assigningFacility = currentFacility
        )
        .blockingGet()

    assertThat(savedBusinessId.uuid).isNotEqualTo(bpPassportCode)
    assertThat(savedBusinessId.patientUuid).isEqualTo(patientProfile.patient.uuid)
    assertThat(savedBusinessId.identifier)
        .isEqualTo(Identifier(value = bpPassportCode, type = BpPassport))
    assertThat(savedBusinessId.metaDataVersion).isEqualTo(BusinessId.MetaDataVersion.BpPassportMetaDataV1)
    assertThat(savedBusinessId.createdAt).isEqualTo(now.plus(duration))
    assertThat(savedBusinessId.updatedAt).isEqualTo(now.plus(duration))
    assertThat(savedBusinessId.deletedAt).isNull()

    val savedMeta = businessIdMetaDataAdapter.deserialize(savedBusinessId.metaData, BusinessId.MetaDataVersion.BpPassportMetaDataV1)
    val expectedSavedMeta = BusinessIdMetaData.BpPassportMetaDataV1(
        assigningUserUuid = loggedInUser.uuid,
        assigningFacilityUuid = currentFacility.uuid
    )
    assertThat(savedMeta).isEqualTo(expectedSavedMeta)

    val (updatedPatient) = patientRepository.patient(patientProfile.patient.uuid).blockingFirst() as Just
    assertThat(updatedPatient.syncStatus).isEqualTo(PENDING)
  }

  @Test
  fun saving_bangladesh_national_identifier_for_a_patient_must_work_as_expected() {
    val patientProfile = testData.patientProfile(syncStatus = DONE, generateBusinessId = false)
    patientRepository.save(listOf(patientProfile)).blockingAwait()

    val nationalId = "33ed3fb2-cfcc-48f8-9b7d-079c02146076"
    val now = Instant.now(clock)

    val duration = Duration.ofDays(1L)
    clock.advanceBy(duration)

    val savedBusinessId = patientRepository
        .addIdentifierToPatient(
            patientUuid = patientProfile.patient.uuid,
            identifier = Identifier(nationalId, BangladeshNationalId),
            assigningUser = loggedInUser,
            assigningFacility = currentFacility
        )
        .blockingGet()

    assertThat(savedBusinessId.uuid).isNotEqualTo(nationalId)
    assertThat(savedBusinessId.patientUuid).isEqualTo(patientProfile.patient.uuid)
    assertThat(savedBusinessId.identifier)
        .isEqualTo(Identifier(value = nationalId, type = BangladeshNationalId))
    assertThat(savedBusinessId.metaDataVersion).isEqualTo(BusinessId.MetaDataVersion.BangladeshNationalIdMetaDataV1)
    assertThat(savedBusinessId.createdAt).isEqualTo(now.plus(duration))
    assertThat(savedBusinessId.updatedAt).isEqualTo(now.plus(duration))
    assertThat(savedBusinessId.deletedAt).isNull()

    val savedMeta = businessIdMetaDataAdapter.deserialize(savedBusinessId.metaData, BusinessId.MetaDataVersion.BangladeshNationalIdMetaDataV1)
    val expectedSavedMeta = BusinessIdMetaData.BangladeshNationalIdMetaDataV1(
        assigningUserUuid = loggedInUser.uuid,
        assigningFacilityUuid = currentFacility.uuid
    )
    assertThat(savedMeta).isEqualTo(expectedSavedMeta)

    val (updatedPatient) = patientRepository.patient(patientProfile.patient.uuid).blockingFirst() as Just
    assertThat(updatedPatient.syncStatus).isEqualTo(PENDING)
  }

  @Test
  fun bangladesh_national_identifier_should_be_saved_if_it_is_not_blank() {
    // given
    val facilityCountry = "Bangladesh"

    val facilityToSavePatientAt = testData.facility(
        uuid = UUID.fromString("d10f34d5-f16c-4095-b345-0867cccf8d06"),
        country = facilityCountry
    )
    val nationalId = "33ed3fb2-cfcc-48f8-9b7d-079c02146076"
    val ongoingPatientEntry = testData.ongoingPatientEntry(
        bangladeshNationalId = Identifier(nationalId, BangladeshNationalId)
    )
    patientRepository.saveOngoingEntry(ongoingPatientEntry).blockingAwait()

    // when
    val savedPatient = patientRepository.saveOngoingEntryAsPatient(loggedInUser, facilityToSavePatientAt).blockingGet()
    val savedPatientNationalId = patientRepository.bangladeshNationalIdForPatient(savedPatient.uuid).blockingFirst().toNullable()!!

    // then
    assertThat(savedPatientNationalId.identifier.value).isEqualTo(nationalId)
  }

  @Test
  fun bangladesh_national_identifier_should_not_be_saved_if_it_is_blank() {
    // given
    val facilityCountry = "Bangladesh"

    val facilityToSavePatientAt = testData.facility(
        uuid = UUID.fromString("d10f34d5-f16c-4095-b345-0867cccf8d06"),
        country = facilityCountry
    )
    val nationalId = ""
    val ongoingPatientEntry = testData.ongoingPatientEntry(
        bangladeshNationalId = Identifier(nationalId, BangladeshNationalId)
    )
    patientRepository.saveOngoingEntry(ongoingPatientEntry).blockingAwait()

    // when
    val savedPatient = patientRepository.saveOngoingEntryAsPatient(loggedInUser, facilityToSavePatientAt).blockingGet()
    val savedPatientNationalId = patientRepository.bangladeshNationalIdForPatient(savedPatient.uuid).blockingFirst()

    // then
    assertThat(savedPatientNationalId).isEqualTo(None)
  }

  @Test
  fun finding_a_patient_by_a_business_id_must_work_as_expected() {
    val patientProfileTemplate = testData.patientProfile(syncStatus = DONE, generateBusinessId = false, generatePhoneNumber = false)

    val uniqueBusinessIdentifier = "unique_business_id"
    val sharedBusinessIdentifier = "shared_business_id"
    val deletedBusinessIdentifier = "deleted_business_id"

    val identifierType = Unknown("test_identifier")
    val metaVersion = BusinessId.MetaDataVersion.Unknown("test_version")
    val now = Instant.now(clock)

    val patientWithUniqueBusinessId = patientProfileTemplate.let { patientProfile ->
      val addressUuid = UUID.randomUUID()
      val patientUuid = UUID.randomUUID()

      val patient = patientProfile.patient.copy(
          uuid = patientUuid,
          fullName = "Patient with unique business ID",
          addressUuid = addressUuid
      )
      val address = patientProfile.address.copy(uuid = addressUuid)
      val businessId = BusinessId(
          uuid = UUID.randomUUID(),
          patientUuid = patientUuid,
          identifier = Identifier(uniqueBusinessIdentifier, identifierType),
          metaDataVersion = metaVersion,
          metaData = "",
          createdAt = now,
          updatedAt = now,
          deletedAt = null
      )

      patientProfile.copy(patient = patient, address = address, businessIds = listOf(businessId))
    }

    val (patientOneWithSharedBusinessId, patientTwoWithSharedBusinessId) = patientProfileTemplate.let { patientProfile ->
      val patientUuidOne = UUID.randomUUID()
      val addressUuidOne = UUID.randomUUID()
      val patientOne = patientProfile.patient.copy(
          uuid = patientUuidOne,
          fullName = "Patient one with shared business ID",
          addressUuid = addressUuidOne,
          createdAt = now.minusSeconds(1),
          updatedAt = now.minusSeconds(1)
      )
      val addressOne = patientProfile.address.copy(uuid = addressUuidOne)
      val businessIdOne = BusinessId(
          uuid = UUID.randomUUID(),
          patientUuid = patientUuidOne,
          identifier = Identifier(sharedBusinessIdentifier, identifierType),
          metaDataVersion = metaVersion,
          metaData = "",
          createdAt = now,
          updatedAt = now,
          deletedAt = null
      )
      val patientProfileOne = patientProfile.copy(patient = patientOne, address = addressOne, businessIds = listOf(businessIdOne))

      val patientUuidTwo = UUID.randomUUID()
      val addressUuidTwo = UUID.randomUUID()
      val patientTwo = patientProfile.patient.copy(
          fullName = "Patient two with shared business ID",
          uuid = patientUuidTwo,
          addressUuid = addressUuidTwo,
          createdAt = now.plusSeconds(1),
          updatedAt = now.plusSeconds(1)
      )
      val addressTwo = patientProfile.address.copy(uuid = addressUuidTwo)
      val businessIdTwo = BusinessId(
          uuid = UUID.randomUUID(),
          patientUuid = patientUuidTwo,
          identifier = Identifier(sharedBusinessIdentifier, identifierType),
          metaDataVersion = metaVersion,
          metaData = "",
          createdAt = now.minusSeconds(1),
          updatedAt = now.minusSeconds(1),
          deletedAt = null
      )
      val patientProfileTwo = patientProfile.copy(patient = patientTwo, address = addressTwo, businessIds = listOf(businessIdTwo))

      patientProfileOne to patientProfileTwo
    }

    val patientWithDeletedBusinessId = patientProfileTemplate.let { patientProfile ->
      val patientUuid = UUID.randomUUID()
      val addressUuid = UUID.randomUUID()

      val patient = patientProfile.patient.copy(
          uuid = patientUuid,
          fullName = "Patient with deleted business ID",
          addressUuid = addressUuid
      )
      val address = patientProfile.address.copy(uuid = addressUuid)
      val businessId = BusinessId(
          uuid = UUID.randomUUID(),
          patientUuid = patientUuid,
          identifier = Identifier(deletedBusinessIdentifier, identifierType),
          metaDataVersion = metaVersion,
          metaData = "",
          createdAt = now,
          updatedAt = now,
          deletedAt = now
      )

      patientProfile.copy(patient = patient, address = address, businessIds = listOf(businessId))
    }


    patientRepository.save(listOf(
        patientWithUniqueBusinessId,
        patientOneWithSharedBusinessId,
        patientTwoWithSharedBusinessId,
        patientWithDeletedBusinessId)
    ).blockingAwait()

    val (patientResultOne) = patientRepository.findPatientWithBusinessId(identifier = uniqueBusinessIdentifier).blockingFirst() as Just<Patient>
    assertThat(patientResultOne).isEqualTo(patientWithUniqueBusinessId.patient)

    val (patientResultTwo) = patientRepository.findPatientWithBusinessId(identifier = sharedBusinessIdentifier).blockingFirst() as Just<Patient>
    assertThat(patientResultTwo).isEqualTo(patientTwoWithSharedBusinessId.patient)

    assertThat(patientRepository.findPatientWithBusinessId(deletedBusinessIdentifier).blockingFirst()).isEqualTo(None)
    assertThat(patientRepository.findPatientWithBusinessId("missing_identifier").blockingFirst()).isEqualTo(None)
  }

  @Test
  fun checking_for_whether_a_patient_is_a_defaulter_should_work_as_expected() {

    fun savePatientRecord(
        fullName: String,
        bpMeasurement: List<BloodPressureMeasurement>?,
        hasHadHeartAttack: Answer = No,
        hasHadStroke: Answer = No,
        hasDiabetes: Answer = No,
        hasHadKidneyDisease: Answer = No,
        protocolDrug: ProtocolDrug?,
        appointmentDate: LocalDate?,
        bloodSugarMeasurement: List<BloodSugarMeasurement>?
    ): Pair<UUID, String> {
      val patientUuid = patientRepository.saveOngoingEntry(testData.ongoingPatientEntry(fullName = fullName))
          .andThen(patientRepository.saveOngoingEntryAsPatient(loggedInUser, currentFacility))
          .blockingGet()
          .uuid

      bpMeasurement?.forEach {
        bloodPressureRepository.save(listOf(testData.bloodPressureMeasurement(
            patientUuid = patientUuid,
            systolic = it.reading.systolic,
            diastolic = it.reading.diastolic,
            recordedAt = it.recordedAt
        ))).blockingAwait()
      }
      bloodSugarMeasurement?.forEach {
        bloodSugarRepository.save(listOf(testData.bloodSugarMeasurement(
            patientUuid = patientUuid,
            reading = it.reading,
            recordedAt = it.recordedAt
        ))).blockingAwait()
      }
      medicalHistoryRepository.save(listOf(testData.medicalHistory(
          patientUuid = patientUuid,
          hasDiabetes = hasDiabetes,
          hasHadHeartAttack = hasHadHeartAttack,
          hasHadKidneyDisease = hasHadKidneyDisease,
          hasHadStroke = hasHadStroke))).blockingAwait()

      protocolDrug?.let {
        prescriptionRepository.savePrescription(
            uuid = UUID.fromString("270dbf0b-604e-4b33-a11c-3b57e2f2125c"),
            patientUuid = patientUuid,
            drug = it,
            facility = currentFacility
        ).blockingAwait()
      }

      appointmentDate?.let {
        appointmentRepository.schedule(
            patientUuid = patientUuid,
            appointmentUuid = UUID.fromString("cf002164-3032-4a91-8dbe-15c0b029db51"),
            appointmentDate = it,
            appointmentType = Manual,
            appointmentFacilityUuid = currentFacility.uuid,
            creationFacilityUuid = currentFacility.uuid
        )
      }

      return patientUuid to fullName
    }

    val patients = mutableListOf<Pair<UUID, String>>()

    patients += savePatientRecord(
        fullName = "Systolic > 140",
        bpMeasurement = listOf(testData.bloodPressureMeasurement(
            systolic = 170,
            diastolic = 80
        )),
        protocolDrug = null,
        appointmentDate = null,
        bloodSugarMeasurement = null)

    patients += savePatientRecord(
        fullName = "Diastolic > 90",
        bpMeasurement = listOf(testData.bloodPressureMeasurement(
            systolic = 130,
            diastolic = 100
        )),
        protocolDrug = null,
        appointmentDate = null,
        bloodSugarMeasurement = null)

    patients += savePatientRecord(
        fullName = "Has had stroke",
        bpMeasurement = listOf(testData.bloodPressureMeasurement(
            systolic = 120,
            diastolic = 70
        )),
        hasHadStroke = Yes,
        protocolDrug = null,
        appointmentDate = null,
        bloodSugarMeasurement = null)

    patients += savePatientRecord(
        fullName = "Had heart attack",
        bpMeasurement = listOf(testData.bloodPressureMeasurement(
            systolic = 120,
            diastolic = 70
        )),
        hasHadHeartAttack = Yes,
        protocolDrug = null,
        appointmentDate = null,
        bloodSugarMeasurement = null)

    patients += savePatientRecord(
        fullName = "Drugs prescribed",
        bpMeasurement = listOf(testData.bloodPressureMeasurement(
            systolic = 120,
            diastolic = 70
        )),
        protocolDrug = testData.protocolDrug(),
        appointmentDate = null,
        bloodSugarMeasurement = null)

    patients += savePatientRecord(
        fullName = "Appointment already scheduled",
        bpMeasurement = listOf(testData.bloodPressureMeasurement(
            systolic = 180,
            diastolic = 70
        )),
        protocolDrug = null,
        appointmentDate = LocalDate.now(clock).plusDays(10),
        bloodSugarMeasurement = null)

    patients += savePatientRecord(
        fullName = "BP deleted, Has had heart attack",
        bpMeasurement = listOf(testData.bloodPressureMeasurement(
            systolic = 180,
            diastolic = 70,
            deletedAt = Instant.now(clock)
        )),
        hasHadHeartAttack = Yes,
        protocolDrug = null,
        appointmentDate = null,
        bloodSugarMeasurement = null)

    patients += savePatientRecord(
        fullName = "Multiple BPs",
        bpMeasurement = listOf(
            testData.bloodPressureMeasurement(
                systolic = 180,
                diastolic = 70,
                recordedAt = Instant.now(clock).minus(40, ChronoUnit.DAYS)),
            testData.bloodPressureMeasurement(
                systolic = 180,
                diastolic = 70,
                recordedAt = Instant.now(clock).minus(10, ChronoUnit.DAYS)
            )),
        protocolDrug = null,
        appointmentDate = null,
        bloodSugarMeasurement = null)

    patients += savePatientRecord(
        fullName = "Last recorded BP is normal",
        bpMeasurement = listOf(
            testData.bloodPressureMeasurement(
                systolic = 180,
                diastolic = 70,
                recordedAt = Instant.now(clock).minus(40, ChronoUnit.DAYS)),
            testData.bloodPressureMeasurement(
                systolic = 120,
                diastolic = 70,
                recordedAt = Instant.now(clock).minus(10, ChronoUnit.DAYS)
            )),
        protocolDrug = null,
        appointmentDate = null,
        bloodSugarMeasurement = null)

    patients += savePatientRecord(
        fullName = "1 Normal Random Blood Sugar",
        bpMeasurement = null,
        protocolDrug = null,
        appointmentDate = null,
        bloodSugarMeasurement = listOf(
            testData.bloodSugarMeasurement(
                reading = BloodSugarReading("120", Random),
                recordedAt = Instant.now(clock).minus(30, ChronoUnit.DAYS)
            )
        )
    )

    patients += savePatientRecord(
        fullName = "1 High Random Blood Sugar",
        bpMeasurement = null,
        protocolDrug = null,
        appointmentDate = null,
        bloodSugarMeasurement = listOf(
            testData.bloodSugarMeasurement(
                reading = BloodSugarReading("350", Random),
                recordedAt = Instant.now(clock).minus(30, ChronoUnit.DAYS)
            )
        )
    )

    patients += savePatientRecord(
        fullName = "Normal BP and High Fasting Blood Sugar",
        bpMeasurement = listOf(
            testData.bloodPressureMeasurement(
                systolic = 122,
                diastolic = 82,
                recordedAt = Instant.now(clock).minus(32, ChronoUnit.DAYS)
            )
        ),
        protocolDrug = null,
        appointmentDate = null,
        bloodSugarMeasurement = listOf(
            testData.bloodSugarMeasurement(
                reading = BloodSugarReading("300", Fasting),
                recordedAt = Instant.now(clock).minus(36, ChronoUnit.DAYS)
            )
        )
    )

    val defaulterPatients = mutableListOf<String>()

    patients.map { (uuid, name) ->
      if (patientRepository.isPatientDefaulter(uuid)) {
        defaulterPatients += name
      }
    }

    assertThat(defaulterPatients).containsExactlyElementsIn(mutableListOf(
        "Systolic > 140",
        "Diastolic > 90",
        "Has had stroke",
        "Had heart attack",
        "Drugs prescribed",
        "BP deleted, Has had heart attack",
        "Multiple BPs",
        "1 High Random Blood Sugar",
        "Normal BP and High Fasting Blood Sugar"))
  }

  @Test
  fun when_fetching_bp_passport_for_patient_then_the_latest_one_should_be_returned() {
    val patientUuid = UUID.randomUUID()
    val identifier = Identifier(patientUuid.toString(), BpPassport)


    val oldBpPassport = testData.businessId(
        patientUuid = patientUuid,
        identifier = identifier,
        createdAt = Instant.now(clock),
        deletedAt = null
    )

    clock.advanceBy(Duration.ofMinutes(10))

    val currentBpPassport = testData.businessId(
        patientUuid = patientUuid,
        identifier = identifier,
        createdAt = Instant.now(clock),
        deletedAt = null
    )
    val deletedBpPassport = testData.businessId(
        patientUuid = patientUuid,
        identifier = identifier,
        createdAt = Instant.now(clock),
        deletedAt = Instant.now(clock)
    )

    val dummyProfile = testData.patientProfile(patientUuid = patientUuid, generateBusinessId = false)
    val profileWithBusinessIds = dummyProfile.copy(businessIds = listOf(oldBpPassport, currentBpPassport, deletedBpPassport))

    patientRepository.save(listOf(profileWithBusinessIds)).blockingAwait()

    val (latestBpPassport) = patientRepository.bpPassportForPatient(patientUuid).blockingFirst()

    assertThat(latestBpPassport).isEqualTo(currentBpPassport)
  }

  @Test
  fun when_updating_patient_recordedAt_then_it_should_compare_and_then_update_the_date() {

    fun createPatientProfile(patientUuid: UUID, recordedAt: Instant): PatientProfile {
      return testData.patientProfile(patientUuid = patientUuid)
          .run {
            copy(patient = patient.copy(
                recordedAt = recordedAt,
                updatedAt = Instant.now(clock),
                syncStatus = DONE)
            )
          }
    }

    val patientUuid1 = UUID.randomUUID()
    val recordedAtForPatient1 = Instant.now(clock)
    val patientProfile1 = createPatientProfile(patientUuid1, recordedAtForPatient1)
    clock.advanceBy(Duration.ofMinutes(1))
    val instantToCompare1 = Instant.now(clock)

    val patientUuid2 = UUID.randomUUID()
    val instantToCompare2 = Instant.now(clock)
    clock.advanceBy(Duration.ofMinutes(1))
    val recordedAtForPatient2 = Instant.now(clock)
    val patientProfile2 = createPatientProfile(patientUuid2, recordedAtForPatient2)

    patientRepository.save(listOf(patientProfile1, patientProfile2)).blockingAwait()

    patientRepository.compareAndUpdateRecordedAt(patientUuid1, instantToCompare1).blockingAwait()
    patientRepository.compareAndUpdateRecordedAt(patientUuid2, instantToCompare2).blockingAwait()

    val patient1 = patientRepository.patient(patientUuid1).blockingFirst().toNullable()!!
    val patient2 = patientRepository.patient(patientUuid2).blockingFirst().toNullable()!!

    assertThat(patient1.recordedAt).isEqualTo(recordedAtForPatient1)
    assertThat(patient1.syncStatus).isEqualTo(DONE)

    assertThat(patient2.recordedAt).isEqualTo(instantToCompare2)
    assertThat(patient2.syncStatus).isEqualTo(PENDING)
    assertThat(patient2.updatedAt).isEqualTo(Instant.now(clock))
  }

  @Test
  fun when_updating_patient_recordedAt_then_it_should_compare_and_then_update_the_date_immediately() {

    fun createPatientProfile(patientUuid: UUID, recordedAt: Instant): PatientProfile {
      return testData.patientProfile(patientUuid = patientUuid)
          .run {
            copy(patient = patient.copy(
                recordedAt = recordedAt,
                updatedAt = Instant.now(clock),
                syncStatus = DONE)
            )
          }
    }

    val patientUuid1 = UUID.randomUUID()
    val recordedAtForPatient1 = Instant.now(clock)
    val patientProfile1 = createPatientProfile(patientUuid1, recordedAtForPatient1)
    clock.advanceBy(Duration.ofMinutes(1))
    val instantToCompare1 = Instant.now(clock)

    val patientUuid2 = UUID.randomUUID()
    val instantToCompare2 = Instant.now(clock)
    clock.advanceBy(Duration.ofMinutes(1))
    val recordedAtForPatient2 = Instant.now(clock)
    val patientProfile2 = createPatientProfile(patientUuid2, recordedAtForPatient2)

    patientRepository.save(listOf(patientProfile1, patientProfile2)).blockingAwait()

    patientRepository.compareAndUpdateRecordedAtImmediate(patientUuid1, instantToCompare1)
    patientRepository.compareAndUpdateRecordedAtImmediate(patientUuid2, instantToCompare2)

    val patient1 = patientRepository.patient(patientUuid1).blockingFirst().toNullable()!!
    val patient2 = patientRepository.patient(patientUuid2).blockingFirst().toNullable()!!

    assertThat(patient1.recordedAt).isEqualTo(recordedAtForPatient1)
    assertThat(patient1.syncStatus).isEqualTo(DONE)

    assertThat(patient2.recordedAt).isEqualTo(instantToCompare2)
    assertThat(patient2.syncStatus).isEqualTo(PENDING)
    assertThat(patient2.updatedAt).isEqualTo(Instant.now(clock))
  }

  @Test
  fun when_patient_recorded_at_needs_to_be_updated_then_it_should_be_set_based_on_oldest_BP() {
    fun loadPatient(patientUuid: UUID) = patientRepository.patient(patientUuid).blockingFirst().toNullable()!!

    val patientUuid = UUID.randomUUID()
    val patientCreatedDate = Instant.now(clock)
    val patient = testData.patientProfile(patientUuid = patientUuid)
        .run {
          copy(patient = patient.copy(
              createdAt = patientCreatedDate,
              recordedAt = patientCreatedDate,
              updatedAt = Instant.now(clock),
              syncStatus = DONE)
          )
        }
    patientRepository.save(listOf(patient)).blockingAwait()

    val bpRecordedTwoDaysBeforePatientCreated = testData.bloodPressureMeasurement(
        patientUuid = patientUuid,
        deletedAt = null,
        recordedAt = patientCreatedDate.minus(2, ChronoUnit.DAYS)
    )
    val bpRecordedOneDayBeforePatientCreated = testData.bloodPressureMeasurement(
        patientUuid = patientUuid,
        deletedAt = null,
        recordedAt = patientCreatedDate.minus(1, ChronoUnit.DAYS)
    )
    val bpRecordedOneMinuteAfterPatientCreated = testData.bloodPressureMeasurement(
        patientUuid = patientUuid,
        deletedAt = null,
        recordedAt = patientCreatedDate.plus(1, ChronoUnit.MINUTES)
    )

    bloodPressureRepository.save(listOf(
        bpRecordedTwoDaysBeforePatientCreated,
        bpRecordedOneDayBeforePatientCreated,
        bpRecordedOneMinuteAfterPatientCreated
    )).blockingAwait()
    patientRepository.updateRecordedAt(patientUuid).blockingAwait()
    loadPatient(patientUuid).let { savedPatient ->
      val expectedRecordedAt = bpRecordedTwoDaysBeforePatientCreated.recordedAt

      assertThat(savedPatient.recordedAt).isEqualTo(expectedRecordedAt)
      assertThat(savedPatient.syncStatus).isEqualTo(PENDING)
      assertThat(savedPatient.updatedAt).isEqualTo(Instant.now(clock))
    }

    bloodPressureRepository.markBloodPressureAsDeleted(bpRecordedTwoDaysBeforePatientCreated).blockingAwait()
    patientRepository.updateRecordedAt(patientUuid).blockingAwait()
    loadPatient(patientUuid).let { savedPatient ->
      val expectedRecordedAt = bpRecordedOneDayBeforePatientCreated.recordedAt

      assertThat(savedPatient.recordedAt).isEqualTo(expectedRecordedAt)
      assertThat(savedPatient.syncStatus).isEqualTo(PENDING)
      assertThat(savedPatient.updatedAt).isEqualTo(Instant.now(clock))
    }

    bloodPressureRepository.markBloodPressureAsDeleted(bpRecordedOneDayBeforePatientCreated).blockingAwait()
    patientRepository.updateRecordedAt(patientUuid).blockingAwait()
    loadPatient(patientUuid).let { savedPatient ->
      assertThat(savedPatient.recordedAt).isEqualTo(patientCreatedDate)
      assertThat(savedPatient.syncStatus).isEqualTo(PENDING)
      assertThat(savedPatient.updatedAt).isEqualTo(Instant.now(clock))
    }

    bloodPressureRepository.markBloodPressureAsDeleted(bpRecordedOneMinuteAfterPatientCreated).blockingAwait()
    patientRepository.updateRecordedAt(patientUuid).blockingAwait()
    loadPatient(patientUuid).let { savedPatient ->
      assertThat(savedPatient.recordedAt).isEqualTo(patientCreatedDate)
      assertThat(savedPatient.syncStatus).isEqualTo(PENDING)
      assertThat(savedPatient.updatedAt).isEqualTo(Instant.now(clock))
    }
  }

  @Test
  fun querying_whether_patient_has_changed_should_work_as_expected() {
    val patientUpdatedAt = Instant.now(clock)
    val patientProfile = testData.patientProfile(syncStatus = PENDING).let { patientProfile ->
      patientProfile.copy(patient = patientProfile.patient.copy(updatedAt = patientUpdatedAt))
    }
    val patientUuid = patientProfile.patient.uuid

    patientRepository.save(listOf(patientProfile)).blockingAwait()

    val oneSecondAfterPatientUpdated = patientUpdatedAt.plus(Duration.ofSeconds(1L))
    val oneSecondBeforePatientUpdated = patientUpdatedAt.minus(Duration.ofSeconds(1L))

    assertThat(patientRepository.hasPatientChangedSince(patientUuid, oneSecondBeforePatientUpdated)).isTrue()
    assertThat(patientRepository.hasPatientChangedSince(patientUuid, patientUpdatedAt)).isFalse()
    assertThat(patientRepository.hasPatientChangedSince(patientUuid, oneSecondAfterPatientUpdated)).isFalse()

    patientRepository.setSyncStatus(listOf(patientUuid), DONE).blockingAwait()

    assertThat(patientRepository.hasPatientChangedSince(patientUuid, patientUpdatedAt)).isFalse()
    assertThat(patientRepository.hasPatientChangedSince(patientUuid, oneSecondAfterPatientUpdated)).isFalse()
    assertThat(patientRepository.hasPatientChangedSince(patientUuid, oneSecondBeforePatientUpdated)).isFalse()
  }

  @Test
  fun patient_edits_should_not_affect_recent_patients() {
    val patient1Uuid = UUID.randomUUID()
    val patient2Uuid = UUID.randomUUID()
    val patient1AddressUuid = UUID.randomUUID()

    val recentPatient1 = savePatientWithBp(patientUuid = patient1Uuid, patientAddressUuid = patient1AddressUuid)
    val recentPatient2 = savePatientWithBp(patientUuid = patient2Uuid)

    verifyRecentPatientOrder(recentPatient2, recentPatient1)

    patientRepository
        .updatePatient(testData.patient(
            uuid = patient1Uuid,
            fullName = "new name",
            addressUuid = patient1AddressUuid,
            age = recentPatient1.age,
            gender = recentPatient1.gender,
            status = Active
        ))
        .blockingAwait()

    verifyRecentPatientOrder(recentPatient2, recentPatient1.copy(fullName = "new name"))
  }

  @Test
  fun patients_that_are_not_active_should_not_come_up_in_recent_patients_list() {
    val facilityUuid = UUID.randomUUID()

    val patient1 = savePatientWithBp(facilityUuid = facilityUuid, patientStatus = Active)
    verifyRecentPatientOrder(patient1, facilityUuid = facilityUuid)

    val patient2 = savePatientWithBp(facilityUuid = facilityUuid, patientStatus = Active)
    verifyRecentPatientOrder(patient2, patient1, facilityUuid = facilityUuid)

    savePatientWithBp(facilityUuid = facilityUuid, patientStatus = Dead)
    verifyRecentPatientOrder(patient2, patient1, facilityUuid = facilityUuid)

    savePatientWithBp(facilityUuid = facilityUuid, patientStatus = Migrated)
    verifyRecentPatientOrder(patient2, patient1, facilityUuid = facilityUuid)

    savePatientWithBp(facilityUuid = facilityUuid, patientStatus = Unresponsive)
    verifyRecentPatientOrder(patient2, patient1, facilityUuid = facilityUuid)

    savePatientWithBp(facilityUuid = facilityUuid, patientStatus = Inactive)
    verifyRecentPatientOrder(patient2, patient1, facilityUuid = facilityUuid)
  }

  @Test
  fun querying_all_patients_in_a_facility_should_return_only_patients_in_that_facility_ordered_by_name() {

    fun recordPatientAtFacilityWithBp(
        patientUuid: UUID,
        patientName: String,
        status: PatientStatus,
        facility: Facility
    ) {
      val patientProfile = testData.patientProfile(patientUuid = patientUuid).let { patientProfile ->
        patientProfile.copy(patient = patientProfile.patient.copy(status = status, fullName = patientName))
      }
      val bp = testData.bloodPressureMeasurement(patientUuid = patientUuid, facilityUuid = facility.uuid)

      patientRepository.save(listOf(patientProfile)).blockingAwait()
      bloodPressureRepository.save(listOf(bp)).blockingAwait()
    }

    fun recordPatientAtFacilityWithBloodSugar(
        patientUuid: UUID,
        patientName: String,
        status: PatientStatus,
        facility: Facility
    ) {
      val patientProfile = testData.patientProfile(patientUuid = patientUuid).let { patientProfile ->
        patientProfile.copy(patient = patientProfile.patient.copy(status = status, fullName = patientName))
      }
      val bloodSugar = testData.bloodSugarMeasurement(patientUuid = patientUuid, facilityUuid = facility.uuid)

      patientRepository.save(listOf(patientProfile)).blockingAwait()
      bloodSugarRepository.save(listOf(bloodSugar)).blockingAwait()
    }

    fun recordPatientAtFacilityWithoutMeasurements(
        patientUuid: UUID,
        patientName: String,
        status: PatientStatus,
        facility: Facility
    ) {
      val patientProfile = testData.patientProfile(patientUuid = patientUuid).let { patientProfile ->
        patientProfile.copy(patient = patientProfile.patient.copy(status = status, fullName = patientName))
      }

      patientRepository.save(listOf(patientProfile)).blockingAwait()
    }

    // given
    val (facilityA, facilityB, facilityC) = facilityRepository.facilities().blockingFirst()

    recordPatientAtFacilityWithBp(
        patientUuid = UUID.fromString("db5ff67d-7004-436c-b42c-0928b7356f10"),
        patientName = "Chitra Naik",
        status = Active,
        facility = facilityA
    )
    recordPatientAtFacilityWithBp(
        patientUuid = UUID.fromString("46faff02-3c94-4b93-b1a2-33043d2bde78"),
        patientName = "Anubhav acharya",
        status = Active,
        facility = facilityA
    )
    recordPatientAtFacilityWithBp(
        patientUuid = UUID.fromString("92c354c6-5e54-43c9-8c02-1c7278b1c5ee"),
        patientName = "Bhim",
        status = Dead,
        facility = facilityA
    )
    recordPatientAtFacilityWithBloodSugar(
        patientUuid = UUID.fromString("e309cbdf-5822-4e4c-b728-cb5b8a344e68"),
        patientName = "Elvis",
        status = Dead,
        facility = facilityB
    )
    recordPatientAtFacilityWithBloodSugar(
        patientUuid = UUID.fromString("742c72f1-615f-4dc0-a4fe-0b0ec0ffc740"),
        patientName = "Farhan",
        status = Dead,
        facility = facilityB
    )
    recordPatientAtFacilityWithBp(
        patientUuid = UUID.fromString("1cede2e9-792a-4d3d-bfce-8795a7c1a388"),
        patientName = "Dhruv",
        status = Active,
        facility = facilityB
    )
    recordPatientAtFacilityWithBp(
        patientUuid = UUID.fromString("277a3ff5-9a83-40eb-8980-ebe02eb0118d"),
        patientName = "Chitra Menon",
        status = Active,
        facility = facilityA
    )
    recordPatientAtFacilityWithBp(
        patientUuid = UUID.fromString("889bf64c-54c5-413c-971a-c10cb8ebf758"),
        patientName = "Anubhav Bansal",
        status = Active,
        facility = facilityA
    )
    recordPatientAtFacilityWithBloodSugar(
        patientUuid = UUID.fromString("9583a07c-cd37-47d7-870e-f6fe9071544c"),
        patientName = "Bhim",
        status = Active,
        facility = facilityB
    )
    recordPatientAtFacilityWithoutMeasurements(
        patientUuid = UUID.fromString("2374f3a8-108e-4548-a1c9-ac3173268e07"),
        patientName = "John Doe",
        status = Active,
        facility = facilityA
    )

    // when
    fun patientsInFacility(facility: Facility): List<PatientSearchResult> {
      return patientRepository
          .allPatientsInFacility(facility)
          .blockingFirst()
    }

    val allPatientFullNamesInFacilityA = patientsInFacility(facilityA).map { it.fullName }
    val allPatientFullNamesInFacilityB = patientsInFacility(facilityB).map { it.fullName }
    val allPatientFullNamesInFacilityC = patientsInFacility(facilityC).map { it.fullName }

    // then
    assertThat(allPatientFullNamesInFacilityA)
        .containsExactly("Anubhav acharya", "Anubhav Bansal", "Chitra Menon", "Chitra Naik")
        .inOrder()

    assertThat(allPatientFullNamesInFacilityB)
        .containsExactly("Bhim", "Dhruv")
        .inOrder()

    assertThat(allPatientFullNamesInFacilityC).isEmpty()
  }

  @Test
  fun querying_all_patients_in_a_facility_should_set_the_last_bp_to_the_last_recorded_one_within_any_facility() {

    fun createPatient(patientUuid: UUID, patientName: String): PatientProfile {
      val patientProfile = testData.patientProfile(patientUuid = patientUuid).let { patientProfile ->
        patientProfile.copy(patient = patientProfile.patient.copy(status = Active, fullName = patientName))
      }
      patientRepository.save(listOf(patientProfile)).blockingAwait()

      return patientProfile
    }

    fun recordBp(uuid: UUID, patientUuid: UUID, facilityUuid: UUID, recordedAt: Instant) {
      val bp = testData.bloodPressureMeasurement(uuid = uuid, patientUuid = patientUuid, facilityUuid = facilityUuid, recordedAt = recordedAt)

      bloodPressureRepository.save(listOf(bp)).blockingAwait()
    }

    // given
    val (facilityA, facilityB) = facilityRepository.facilities().blockingFirst()
    val now = Instant.parse("2019-06-26T00:00:00Z")
    val oneSecondLater = now.plusSeconds(1)
    val oneSecondEarlier = now.minusSeconds(1)
    val fiveSecondsLater = now.plusSeconds(5)

    val uuidOfPatientA = UUID.fromString("e2a49529-3b46-4c9e-a7bc-af3090f1ecbb")
    val uuidOfBp1OfPatientA = UUID.fromString("588b0d8c-6dce-4664-863f-8c79c06c6c3c")
    val uuidOfBp2OfPatientA = UUID.fromString("ba875b98-4ca0-4097-bbc7-56041b377cf2")
    val uuidOfBp3OfPatientA = UUID.fromString("4b92601e-4a86-40de-b6b7-707f9122a81b")
    createPatient(patientUuid = uuidOfPatientA, patientName = "Patient with latest BP in Facility A")
    recordBp(uuid = uuidOfBp1OfPatientA, patientUuid = uuidOfPatientA, facilityUuid = facilityA.uuid, recordedAt = oneSecondLater)
    recordBp(uuid = uuidOfBp2OfPatientA, patientUuid = uuidOfPatientA, facilityUuid = facilityB.uuid, recordedAt = oneSecondEarlier)
    recordBp(uuid = uuidOfBp3OfPatientA, patientUuid = uuidOfPatientA, facilityUuid = facilityA.uuid, recordedAt = now)

    val uuidOfPatientB = UUID.fromString("7925e13f-3b04-46b0-b685-7005ebb1b6fd")
    val uuidOfBp1OfPatientB = UUID.fromString("00e39456-f5e4-4538-956d-7e973ec5da88")
    val uuidOfBp2OfPatientB = UUID.fromString("b72e28cd-1b46-4c72-84cd-58530c2829e3")
    val uuidOfBp3OfPatientB = UUID.fromString("c18b41d5-260e-4a26-8030-45c621ded98d")
    createPatient(patientUuid = uuidOfPatientB, patientName = "Patient with latest BP in Facility B")
    recordBp(uuid = uuidOfBp1OfPatientB, patientUuid = uuidOfPatientB, facilityUuid = facilityB.uuid, recordedAt = fiveSecondsLater)
    recordBp(uuid = uuidOfBp2OfPatientB, patientUuid = uuidOfPatientB, facilityUuid = facilityA.uuid, recordedAt = oneSecondEarlier)
    recordBp(uuid = uuidOfBp3OfPatientB, patientUuid = uuidOfPatientB, facilityUuid = facilityB.uuid, recordedAt = now)

    val uuidOfPatientC = UUID.fromString("9fe841eb-5ae3-404b-99d6-87455cc87eda")
    createPatient(patientUuid = uuidOfPatientC, patientName = "Patient with no BPs")

    // when
    fun patientsInFacility(facility: Facility): List<PatientSearchResult> {
      return patientRepository
          .allPatientsInFacility(facility)
          .blockingFirst()
    }

    data class PatientUuidAndLatestBpRecorded(val patientUuid: UUID, val lastSeen: LastSeen) {
      constructor(patientSearchResult: PatientSearchResult) : this(patientSearchResult.uuid, patientSearchResult.lastSeen!!)
    }

    val patientsAndLatestBpRecordedAtFacilityA = patientsInFacility(facilityA)
        .map(::PatientUuidAndLatestBpRecorded)
    val patientsAndLatestBpRecordedAtFacilityB = patientsInFacility(facilityB)
        .map(::PatientUuidAndLatestBpRecorded)

    // then
    assertThat(patientsAndLatestBpRecordedAtFacilityA)
        .containsExactly(
            PatientUuidAndLatestBpRecorded(
                patientUuid = uuidOfPatientA,
                lastSeen = LastSeen(
                    lastSeenOn = oneSecondLater,
                    lastSeenAtFacilityName = facilityA.name,
                    lastSeenAtFacilityUuid = facilityA.uuid
                )
            ),
            PatientUuidAndLatestBpRecorded(
                patientUuid = uuidOfPatientB,
                lastSeen = LastSeen(
                    lastSeenOn = fiveSecondsLater,
                    lastSeenAtFacilityName = facilityB.name,
                    lastSeenAtFacilityUuid = facilityB.uuid
                )
            )
        )
        .inOrder()

    assertThat(patientsAndLatestBpRecordedAtFacilityB)
        .containsExactly(
            PatientUuidAndLatestBpRecorded(
                patientUuid = uuidOfPatientA,
                lastSeen = LastSeen(
                    lastSeenOn = oneSecondLater,
                    lastSeenAtFacilityName = facilityA.name,
                    lastSeenAtFacilityUuid = facilityA.uuid
                )
            ),
            PatientUuidAndLatestBpRecorded(
                patientUuid = uuidOfPatientB,
                lastSeen = LastSeen(
                    lastSeenOn = fiveSecondsLater,
                    lastSeenAtFacilityName = facilityB.name,
                    lastSeenAtFacilityUuid = facilityB.uuid
                )
            )
        )
        .inOrder()
  }

  @Test
  fun querying_all_patients_in_a_facility_should_set_the_the_latest_recorded_measurement_recorded_on_within_any_facility() {

    fun createPatient(patientUuid: UUID, patientName: String): PatientProfile {
      val patientProfile = testData.patientProfile(patientUuid = patientUuid).let { patientProfile ->
        patientProfile.copy(patient = patientProfile.patient.copy(status = Active, fullName = patientName))
      }
      patientRepository.save(listOf(patientProfile)).blockingAwait()

      return patientProfile
    }

    fun recordBp(uuid: UUID, patientUuid: UUID, facilityUuid: UUID, recordedAt: Instant) {
      val bp = testData.bloodPressureMeasurement(uuid = uuid, patientUuid = patientUuid, facilityUuid = facilityUuid, recordedAt = recordedAt)

      bloodPressureRepository.save(listOf(bp)).blockingAwait()
    }

    fun recordBloodSugar(uuid: UUID, patientUuid: UUID, facilityUuid: UUID, recordedAt: Instant) {
      val bloodSugar = testData.bloodSugarMeasurement(uuid = uuid, patientUuid = patientUuid, facilityUuid = facilityUuid, recordedAt = recordedAt)

      bloodSugarRepository.save(listOf(bloodSugar)).blockingAwait()
    }

    // given
    val (facilityA, facilityB) = facilityRepository.facilities().blockingFirst()
    val now = Instant.parse("2019-06-26T00:00:00Z")
    val oneSecondLater = now.plusSeconds(1)
    val oneSecondEarlier = now.minusSeconds(1)
    val fiveSecondsLater = now.plusSeconds(5)

    val uuidOfPatientA = UUID.fromString("e2a49529-3b46-4c9e-a7bc-af3090f1ecbb")
    val uuidOfBloodSugar1OfPatientA = UUID.fromString("588b0d8c-6dce-4664-863f-8c79c06c6c3c")
    val uuidOfBp1OfPatientA = UUID.fromString("ba875b98-4ca0-4097-bbc7-56041b377cf2")
    val uuidOfBloodSugar2OfPatientA = UUID.fromString("4b92601e-4a86-40de-b6b7-707f9122a81b")
    createPatient(patientUuid = uuidOfPatientA, patientName = "Patient with latest BP in Facility A")
    recordBloodSugar(uuid = uuidOfBloodSugar1OfPatientA, patientUuid = uuidOfPatientA, facilityUuid = facilityA.uuid, recordedAt = oneSecondLater)
    recordBp(uuid = uuidOfBp1OfPatientA, patientUuid = uuidOfPatientA, facilityUuid = facilityB.uuid, recordedAt = oneSecondEarlier)
    recordBloodSugar(uuid = uuidOfBloodSugar2OfPatientA, patientUuid = uuidOfPatientA, facilityUuid = facilityA.uuid, recordedAt = now)

    val uuidOfPatientB = UUID.fromString("7925e13f-3b04-46b0-b685-7005ebb1b6fd")
    val uuidOfBp1OfPatientB = UUID.fromString("00e39456-f5e4-4538-956d-7e973ec5da88")
    val uuidOfBp2OfPatientB = UUID.fromString("c18b41d5-260e-4a26-8030-45c621ded98d")
    val uuidOfBloodSugar1OfPatientB = UUID.fromString("b72e28cd-1b46-4c72-84cd-58530c2829e3")
    createPatient(patientUuid = uuidOfPatientB, patientName = "Patient with latest BP in Facility B")
    recordBp(uuid = uuidOfBp1OfPatientB, patientUuid = uuidOfPatientB, facilityUuid = facilityB.uuid, recordedAt = fiveSecondsLater)
    recordBloodSugar(uuid = uuidOfBloodSugar1OfPatientB, patientUuid = uuidOfPatientB, facilityUuid = facilityA.uuid, recordedAt = oneSecondEarlier)
    recordBp(uuid = uuidOfBp2OfPatientB, patientUuid = uuidOfPatientB, facilityUuid = facilityB.uuid, recordedAt = now)

    val uuidOfPatientC = UUID.fromString("9fe841eb-5ae3-404b-99d6-87455cc87eda")
    createPatient(patientUuid = uuidOfPatientC, patientName = "Patient with no BPs")

    // when
    fun patientsInFacility(facility: Facility): List<PatientSearchResult> {
      return patientRepository
          .allPatientsInFacility(facility)
          .blockingFirst()
    }

    data class PatientUuidAndLatestMeasurementRecorded(val patientUuid: UUID, val lastSeen: LastSeen) {
      constructor(patientSearchResult: PatientSearchResult) : this(patientSearchResult.uuid, patientSearchResult.lastSeen!!)
    }

    val patientsAndLatestBpRecordedAtFacilityA = patientsInFacility(facilityA)
        .map(::PatientUuidAndLatestMeasurementRecorded)
    val patientsAndLatestBpRecordedAtFacilityB = patientsInFacility(facilityB)
        .map(::PatientUuidAndLatestMeasurementRecorded)

    // then
    assertThat(patientsAndLatestBpRecordedAtFacilityA)
        .containsExactly(
            PatientUuidAndLatestMeasurementRecorded(
                patientUuid = uuidOfPatientA,
                lastSeen = LastSeen(
                    lastSeenOn = oneSecondLater,
                    lastSeenAtFacilityName = facilityA.name,
                    lastSeenAtFacilityUuid = facilityA.uuid
                )
            ),
            PatientUuidAndLatestMeasurementRecorded(
                patientUuid = uuidOfPatientB,
                lastSeen = LastSeen(
                    lastSeenOn = fiveSecondsLater,
                    lastSeenAtFacilityName = facilityB.name,
                    lastSeenAtFacilityUuid = facilityB.uuid
                )
            )
        )
        .inOrder()

    assertThat(patientsAndLatestBpRecordedAtFacilityB)
        .containsExactly(
            PatientUuidAndLatestMeasurementRecorded(
                patientUuid = uuidOfPatientA,
                lastSeen = LastSeen(
                    lastSeenOn = oneSecondLater,
                    lastSeenAtFacilityName = facilityA.name,
                    lastSeenAtFacilityUuid = facilityA.uuid
                )
            ),
            PatientUuidAndLatestMeasurementRecorded(
                patientUuid = uuidOfPatientB,
                lastSeen = LastSeen(
                    lastSeenOn = fiveSecondsLater,
                    lastSeenAtFacilityName = facilityB.name,
                    lastSeenAtFacilityUuid = facilityB.uuid
                )
            )
        )
        .inOrder()
  }

  @Test
  fun verify_recordedAt_is_being_used_for_bp_instead_of_updatedAt_for_recent_patients() {
    val initialTime = clock.instant()

    val patient1 = savePatientWithBp(
        recordedAt = initialTime,
        updatedAt = initialTime.plusSeconds(100)
    )
    verifyRecentPatientOrder(patient1)

    val patient2 = savePatientWithBp(
        recordedAt = initialTime.plusSeconds(10),
        updatedAt = initialTime.plusSeconds(50)
    )
    verifyRecentPatientOrder(patient2, patient1)
  }

  @Test
  fun searching_for_a_patient_by_phone_number_must_return_all_patients_with_a_matching_phone_number() {
    fun createPatientWithPhoneNumber(
        patientUuid: UUID,
        phoneNumber: String?
    ) {
      val patientProfile = testData
          .patientProfile(patientUuid = patientUuid, generatePhoneNumber = !phoneNumber.isNullOrBlank())
          .let { patientProfile ->
            patientProfile.copy(
                patient = patientProfile.patient.copy(fullName = "Patient"),
                phoneNumbers = patientProfile
                    .phoneNumbers
                    .take(1)
                    .map { it.copy(number = phoneNumber!!) }
            )
          }

      patientRepository.save(listOf(patientProfile)).blockingAwait()
    }

    fun searchResults(phoneNumber: String): Set<UUID> {
      return patientRepository
          .search(PhoneNumber(phoneNumber))
          .blockingFirst()
          .map { it.uuid }
          .toSet()
    }

    // given
    val patientWithExactlyMatchingNumber = UUID.fromString("d47ae2e7-7453-4a6f-806e-88eb130823d8")
    createPatientWithPhoneNumber(
        patientUuid = patientWithExactlyMatchingNumber,
        phoneNumber = "1234567890"
    )
    val patientWithFirstPartMatchingNumber = UUID.fromString("bd050289-29c3-4459-8d33-50e8d77469d2")
    createPatientWithPhoneNumber(
        patientUuid = patientWithFirstPartMatchingNumber,
        phoneNumber = "1234588888"
    )
    val patientWithLastPartMatchingNumber = UUID.fromString("ff3552b7-83f2-47f6-b840-c6ce12de6ad5")
    createPatientWithPhoneNumber(
        patientUuid = patientWithLastPartMatchingNumber,
        phoneNumber = "1111167890"
    )
    val patientWithMiddlePartMatchingNumber = UUID.fromString("b7aa9310-6ae9-4ac2-b040-762f5cc5a505")
    createPatientWithPhoneNumber(
        patientUuid = patientWithMiddlePartMatchingNumber,
        phoneNumber = "1114567800"
    )
    val patientWithNoPartMatchingNumber = UUID.fromString("0d955585-6887-451f-9b20-94587938a09d")
    createPatientWithPhoneNumber(
        patientUuid = patientWithNoPartMatchingNumber,
        phoneNumber = "0000000000"
    )
    val patientWithoutAnyNumber = UUID.fromString("e8ad5b9a-2b27-4d20-9257-c9c456d5f168")
    createPatientWithPhoneNumber(
        patientUuid = patientWithoutAnyNumber,
        phoneNumber = null
    )

    // when
    val searchResultsWithExactMatch = searchResults("1234567890")
    val searchResultsWithFirstPartMatching = searchResults("12345")
    val searchResultsWithLastPartMatching = searchResults("67890")
    val searchResultsWithMiddlePartMatching = searchResults("45678")
    val searchResultsWithNoPartMatching = searchResults("22222")

    // then
    assertThat(searchResultsWithExactMatch)
        .containsExactly(patientWithExactlyMatchingNumber)
    assertThat(searchResultsWithFirstPartMatching)
        .containsExactly(
            patientWithFirstPartMatchingNumber,
            patientWithExactlyMatchingNumber
        )
    assertThat(searchResultsWithLastPartMatching)
        .containsExactly(
            patientWithLastPartMatchingNumber,
            patientWithExactlyMatchingNumber
        )
    assertThat(searchResultsWithMiddlePartMatching)
        .containsExactly(
            patientWithMiddlePartMatchingNumber,
            patientWithExactlyMatchingNumber
        )
    assertThat(searchResultsWithNoPartMatching)
        .isEmpty()
  }

  @Test
  fun searching_for_a_patient_by_phone_number_must_return_results_ordered_by_patient_name() {
    fun createPatientWithPhoneNumber(
        patientName: String,
        phoneNumber: String
    ) {

      val patientProfile = testData
          .patientProfile(patientUuid = UUID.randomUUID(), generatePhoneNumber = !phoneNumber.isBlank())
          .let { patientProfile ->
            patientProfile.copy(
                patient = patientProfile.patient.copy(fullName = patientName),
                phoneNumbers = patientProfile
                    .phoneNumbers
                    .take(1)
                    .map { it.copy(number = phoneNumber) }
            )
          }

      patientRepository.save(listOf(patientProfile)).blockingAwait()
    }

    fun searchResults(phoneNumber: String): List<String> {
      return patientRepository
          .search(PhoneNumber(phoneNumber))
          .blockingFirst()
          .map { it.fullName }
    }

    // given
    createPatientWithPhoneNumber(patientName = "Abhinav", phoneNumber = "1234567890")
    createPatientWithPhoneNumber(patientName = "Chitra", phoneNumber = "1234567890")
    createPatientWithPhoneNumber(patientName = "abhinav", phoneNumber = "1234567890")
    createPatientWithPhoneNumber(patientName = "Fatima", phoneNumber = "1234567890")
    createPatientWithPhoneNumber(patientName = "esther d' souza", phoneNumber = "1234567890")
    createPatientWithPhoneNumber(patientName = "Dhruv Saxena", phoneNumber = "1234567890")
    createPatientWithPhoneNumber(patientName = "Dhruv Acharya", phoneNumber = "1234567890")

    // when
    val searchResults = searchResults(phoneNumber = "1234567890")
        // Converting to lowercase because the ordering of the search results for names which are
        // exactly the same but in different cases is undefined. Different runs of the tests will
        // sometimes report the names "Abhinav" and "abhinav" as ["Abhinav", "abhinav"] and
        // sometimes as ["abhinav", "Abhinav"], which causes flakiness.
        .map { it.toLowerCase(Locale.ENGLISH) }

    // then
    assertThat(searchResults)
        .containsExactly("abhinav", "abhinav", "chitra", "dhruv acharya", "dhruv saxena", "esther d' souza", "fatima")
        .inOrder()
  }

  @Test
  fun searching_for_a_patient_by_phone_number_must_return_results_with_the_latest_bp_set() {
    fun createPatientWithBps(
        patientUuid: UUID,
        bpMeasurements: List<BloodPressureMeasurement>
    ) {

      val patientProfile = testData
          .patientProfile(patientUuid = patientUuid, generatePhoneNumber = false)
          .let { patientProfile ->
            patientProfile.copy(
                phoneNumbers = listOf(testData.patientPhoneNumber(patientUuid = patientUuid, number = "1234567890"))
            )
          }

      patientRepository.save(listOf(patientProfile)).blockingAwait()
      bloodPressureRepository.save(bpMeasurements).blockingAwait()
    }

    fun searchResults(phoneNumber: String): Map<UUID, Optional<LastSeen>> {
      return patientRepository
          .search(PhoneNumber(phoneNumber))
          .blockingFirst()
          .associateBy({ it.uuid }, { it.lastSeen.toOptional() })
    }

    // given
    val facilityUuid = currentFacility.uuid
    val facilityName = currentFacility.name

    val instant = Instant.parse("2019-01-01T00:00:00Z")

    val patientWithNoBps = UUID.fromString("4e3bf233-7e28-4b80-9547-a2d9370ee64f")

    val patientWithOneBp = UUID.fromString("b8eeec92-4dfe-4b69-96f7-ff022ddaadbc")
    val bpUuidOfPatientWithOneBp = UUID.fromString("71ffbfcd-fa76-4d4e-8083-fac89bff9e89")

    val patientWithMultipleBps = UUID.fromString("6a2d9f6b-bf5a-4132-9999-df2a7333cf49")
    val oldestBpUuidOfPatientWithMultipleBps = UUID.fromString("9a1fe98e-39eb-481b-a215-cba8ad079960")
    val newerNonDeletedBpUuidOfPatientWithMultipleBps = UUID.fromString("46aa741e-8f18-4ba8-a278-41706eb21f80")
    val latestDeletedBpUuidOfPatientWithMultipleBps = UUID.fromString("919dfaef-4c37-4a11-a1f0-1e96147a55fd")

    createPatientWithBps(
        patientUuid = patientWithNoBps,
        bpMeasurements = emptyList()
    )
    createPatientWithBps(
        patientUuid = patientWithOneBp,
        bpMeasurements = listOf(
            testData.bloodPressureMeasurement(
                uuid = bpUuidOfPatientWithOneBp,
                patientUuid = patientWithOneBp,
                recordedAt = instant,
                facilityUuid = facilityUuid
            )
        )
    )
    createPatientWithBps(
        patientUuid = patientWithMultipleBps,
        bpMeasurements = listOf(
            testData.bloodPressureMeasurement(
                uuid = oldestBpUuidOfPatientWithMultipleBps,
                patientUuid = patientWithMultipleBps,
                recordedAt = instant,
                facilityUuid = facilityUuid
            ),
            testData.bloodPressureMeasurement(
                uuid = newerNonDeletedBpUuidOfPatientWithMultipleBps,
                patientUuid = patientWithMultipleBps,
                recordedAt = instant.plusSeconds(1L),
                facilityUuid = facilityUuid
            ),
            testData.bloodPressureMeasurement(
                uuid = latestDeletedBpUuidOfPatientWithMultipleBps,
                patientUuid = patientWithMultipleBps,
                recordedAt = instant.plusSeconds(2L),
                deletedAt = instant.plusSeconds(5L),
                facilityUuid = facilityUuid
            )
        )
    )

    // when
    val searchResults = searchResults(phoneNumber = "1234567890")

    // then
    val expectedResults = mapOf(
        patientWithNoBps to None,
        patientWithOneBp to LastSeen(
            lastSeenOn = instant,
            lastSeenAtFacilityName = facilityName,
            lastSeenAtFacilityUuid = facilityUuid
        ).toOptional(),
        patientWithMultipleBps to LastSeen(
            lastSeenOn = instant.plusSeconds(1L),
            lastSeenAtFacilityName = facilityName,
            lastSeenAtFacilityUuid = facilityUuid
        ).toOptional()
    )
    assertThat(searchResults).isEqualTo(expectedResults)
  }

  @Test
  fun searching_for_patients_by_short_code_must_return_all_the_matching_results() {
    fun createPatientWithBPPassport(patientUuid: UUID, bpPassportId: String) {

      val patientProfile = testData.patientProfile(patientUuid = patientUuid, generateBusinessId = false)

      patientRepository.save(listOf(patientProfile)).blockingAwait()
      patientRepository
          .addIdentifierToPatient(
              patientUuid = patientProfile.patient.uuid,
              identifier = Identifier(bpPassportId, BpPassport),
              assigningUser = loggedInUser,
              assigningFacility = currentFacility
          )
          .blockingGet()
    }


    //given
    createPatientWithBPPassport(UUID.fromString("97d05796-614c-46de-a10a-e12cf595f4ff"), "3824f7e7-d0a9-4ae0-a0da-3230c53cdfb8")
    createPatientWithBPPassport(UUID.fromString("0803356a-1079-46ac-9a3b-5763be2b5506"), "17671df1-1727-45f2-bf06-0e02c990c847")
    createPatientWithBPPassport(UUID.fromString("4e642ef2-1991-42ae-ba61-a10809c78f5d"), "3824f7e7-d0a9-4ae0-a0da-3230c53cdfb8")
    createPatientWithBPPassport(UUID.fromString("0a549d43-7288-4633-b968-2ed91736edbe"), "884402f4-6066-41ac-a8b4-06a620d0ca5d")
    createPatientWithBPPassport(UUID.fromString("b551fec9-b707-43e8-aa44-2ad444943229"), "199b123e-4664-4720-a184-494598d9b37d")

    val searchedShortCode = "3824770"

    //when
    val shortCodeSearchResults = patientRepository.searchByShortCode(searchedShortCode).blockingFirst()

    //then
    assertThat(shortCodeSearchResults.map { it.uuid.toString() })
        .containsExactly("97d05796-614c-46de-a10a-e12cf595f4ff", "4e642ef2-1991-42ae-ba61-a10809c78f5d")
  }

  @Test
  fun deleted_patients_must_be_excluded_when_searching_by_name() {
    fun createPatient(
        patientUuid: UUID,
        patientName: String,
        isDeleted: Boolean
    ) {
      val patientProfile = testData.patientProfile(
          patientUuid = patientUuid,
          patientName = patientName,
          patientStatus = Active,
          patientDeletedAt = if (isDeleted) Instant.now() else null
      )

      patientRepository.save(listOf(patientProfile)).blockingAwait()
    }

    // given
    val deletedPatientId = UUID.fromString("01447197-81ca-4eb8-b8bb-c0a16393f587")
    createPatient(deletedPatientId, "Patient that is deleted", isDeleted = true)
    val notDeletedPatientId = UUID.fromString("caef6b04-ed06-4d0c-8210-3ee0e51e6ee8")
    createPatient(notDeletedPatientId, "Patient that is not deleted", isDeleted = false)

    // when
    val searchResults = patientRepository.search(Name("Patient")).blockingFirst()

    // then
    assertThat(searchResults).hasSize(1)
    assertThat(searchResults.first().uuid).isEqualTo(notDeletedPatientId)
  }

  @Test
  fun deleted_patients_must_be_excluded_when_searching_by_phone_number() {
    fun createPatient(
        patientUuid: UUID,
        phoneNumber: String,
        isDeleted: Boolean
    ) {
      val patientProfile = testData.patientProfile(
          patientUuid = patientUuid,
          patientPhoneNumber = phoneNumber,
          patientStatus = Active,
          patientDeletedAt = if (isDeleted) Instant.now() else null
      )

      patientRepository.save(listOf(patientProfile)).blockingAwait()
    }

    // given
    val deletedPatientId = UUID.fromString("01447197-81ca-4eb8-b8bb-c0a16393f587")
    createPatient(deletedPatientId, "1234567890", isDeleted = true)
    val notDeletedPatientId = UUID.fromString("caef6b04-ed06-4d0c-8210-3ee0e51e6ee8")
    createPatient(notDeletedPatientId, "1234567890", isDeleted = false)

    // when
    val searchResults = patientRepository.search(PhoneNumber("1234567890")).blockingFirst()

    // then
    assertThat(searchResults).hasSize(1)
    assertThat(searchResults.first().uuid).isEqualTo(notDeletedPatientId)
  }

  @Test
  fun deleted_patients_must_be_excluded_when_searching_by_shortcode() {
    fun createPatientWithBPPassport(
        patientUuid: UUID,
        bpPassportId: String,
        isDeleted: Boolean
    ) {
      val patientProfile = testData.patientProfile(
          patientUuid = patientUuid,
          generateBusinessId = false,
          patientDeletedAt = if (isDeleted) Instant.now() else null
      )

      patientRepository.save(listOf(patientProfile)).blockingAwait()
      patientRepository
          .addIdentifierToPatient(
              patientUuid = patientProfile.patient.uuid,
              identifier = Identifier(bpPassportId, BpPassport),
              assigningUser = loggedInUser,
              assigningFacility = currentFacility
          )
          .blockingGet()
    }


    //given
    val deletedPatientId = UUID.fromString("97d05796-614c-46de-a10a-e12cf595f4ff")
    createPatientWithBPPassport(deletedPatientId, "3824f7e7-d0a9-4ae0-a0da-3230c53cdfb8", isDeleted = true)
    val notDeletedPatientId = UUID.fromString("4e642ef2-1991-42ae-ba61-a10809c78f5d")
    createPatientWithBPPassport(notDeletedPatientId, "3824f7e7-d0a8-4ae0-a0da-3230c53cdfb8", isDeleted = false)

    val searchedShortCode = "3824770"

    //when
    val searchResults = patientRepository.searchByShortCode(searchedShortCode).blockingFirst()

    //then
    assertThat(searchResults).hasSize(1)
    assertThat(searchResults.first().uuid).isEqualTo(notDeletedPatientId)
  }

  @Test
  fun deleted_patients_must_be_excluded_when_loading_all_patients_in_a_facility() {
    fun recordPatientAtFacilityWithBP(
        patientUuid: UUID,
        patientName: String,
        facility: Facility,
        isDeleted: Boolean
    ) {
      val patientProfile = testData.patientProfile(
          patientUuid = patientUuid,
          patientName = patientName,
          patientDeletedAt = if (isDeleted) Instant.now() else null
      )
      val bp = testData.bloodPressureMeasurement(patientUuid = patientUuid, facilityUuid = facility.uuid)

      patientRepository.save(listOf(patientProfile)).blockingAwait()
      bloodPressureRepository.save(listOf(bp)).blockingAwait()
    }

    fun recordPatientAtFacilityWithBloodSugar(
        patientUuid: UUID,
        patientName: String,
        facility: Facility,
        isDeleted: Boolean
    ) {
      val patientProfile = testData.patientProfile(
          patientUuid = patientUuid,
          patientName = patientName,
          patientDeletedAt = if (isDeleted) Instant.now() else null
      )
      val bloodSugarMeasurement = testData.bloodSugarMeasurement(patientUuid = patientUuid, facilityUuid = facility.uuid)

      patientRepository.save(listOf(patientProfile)).blockingAwait()
      bloodSugarRepository.save(listOf(bloodSugarMeasurement)).blockingAwait()
    }

    //given
    val deletedPatientIdWithBP = UUID.fromString("97d05796-614c-46de-a10a-e12cf595f4ff")
    recordPatientAtFacilityWithBP(
        patientUuid = deletedPatientIdWithBP,
        patientName = "John Doe",
        facility = currentFacility,
        isDeleted = true
    )
    val notDeletedPatientIdWithBP = UUID.fromString("4e642ef2-1991-42ae-ba61-a10809c78f5d")
    recordPatientAtFacilityWithBP(
        patientUuid = notDeletedPatientIdWithBP,
        patientName = "John Wick",
        facility = currentFacility,
        isDeleted = false
    )
    val deletedPatientIdWithBloodSugar = UUID.fromString("6042abbc-9ddc-4f6c-aa4d-befb1d2c04a1")
    recordPatientAtFacilityWithBloodSugar(
        patientUuid = deletedPatientIdWithBloodSugar,
        patientName = "Sarah Connor",
        facility = currentFacility,
        isDeleted = true
    )
    val notDeletedPatientIdWithBloodSugar = UUID.fromString("e5db5392-9fec-41fa-9633-4213ddad5903")
    recordPatientAtFacilityWithBloodSugar(
        patientUuid = notDeletedPatientIdWithBloodSugar,
        patientName = "Jane Doe",
        facility = currentFacility,
        isDeleted = false
    )

    //when
    val allPatientsInFacility = patientRepository.allPatientsInFacility(currentFacility).blockingFirst()

    //then
    assertThat(allPatientsInFacility).hasSize(2)
    assertThat(allPatientsInFacility.map { it.uuid })
        .containsExactly(notDeletedPatientIdWithBloodSugar, notDeletedPatientIdWithBP)
        .inOrder()
  }

  @Test
  fun deleted_patients_must_be_excluded_when_finding_by_business_id() {
    fun recordPatientWithBusinessId(
        patientUuid: UUID,
        bpPassportUuid: UUID,
        identifier: String,
        isDeleted: Boolean
    ) {
      val patientProfile = testData.patientProfile(
          patientUuid = patientUuid,
          businessId = testData.businessId(
              uuid = bpPassportUuid,
              patientUuid = patientUuid,
              identifier = testData.identifier(value = identifier, type = BpPassport)
          ),
          patientDeletedAt = if (isDeleted) Instant.now() else null
      )

      patientRepository.save(listOf(patientProfile)).blockingAwait()
    }

    fun findPatientWithIdentifier(identifier: String): Optional<Patient> {
      return patientRepository.findPatientWithBusinessId(identifier).blockingFirst()
    }

    //given
    val deletedPatientId = UUID.fromString("97d05796-614c-46de-a10a-e12cf595f4ff")
    val deletedPatientIdentifier = "2fc3e465-4d9b-4e30-8fd3-592055837a39"
    recordPatientWithBusinessId(
        patientUuid = deletedPatientId,
        bpPassportUuid = UUID.fromString("764fd0ff-0e62-4f92-bef8-3e651e81a1fe"),
        identifier = deletedPatientIdentifier,
        isDeleted = true
    )
    val notDeletedPatientId = UUID.fromString("4e642ef2-1991-42ae-ba61-a10809c78f5d")
    val notDeletedPatientIdentifier = "6dea3680-72a6-495b-ad5b-b52391af0dbf"
    recordPatientWithBusinessId(
        patientUuid = notDeletedPatientId,
        bpPassportUuid = UUID.fromString("60aae36c-5d46-4fdd-8833-bfc55c8f4154"),
        identifier = notDeletedPatientIdentifier,
        isDeleted = false
    )

    // when
    val resultForDeletedPatient = findPatientWithIdentifier(deletedPatientIdentifier)
    val resultForNotDeletedPatient = findPatientWithIdentifier(notDeletedPatientIdentifier) as Just<Patient>

    //then
    assertThat(resultForDeletedPatient).isEqualTo(None)
    assertThat(resultForNotDeletedPatient.value.uuid).isEqualTo(notDeletedPatientId)
  }

  @Test
  fun a_patient_must_be_saved_with_the_country_of_the_facility() {
    // given
    val facilityCountry = "Bangladesh"
    val userEnteredPatientColony = "Test Colony"
    val userEnteredPatientDistrict = "Test District"
    val userEnteredPatientState = "Test State"
    val userEnteredPatientStreetAddress = "Street Address"
    val userEnteredPatientZone = "Zone"

    val facilityToSavePatientAt = testData.facility(
        uuid = UUID.fromString("d10f34d5-f16c-4095-b345-0867cccf8d06"),
        country = facilityCountry
    )
    val ongoingPatientEntry = testData.ongoingPatientEntry(
        colony = userEnteredPatientColony,
        district = userEnteredPatientDistrict,
        state = userEnteredPatientState,
        streetAddress = userEnteredPatientStreetAddress,
        zone = userEnteredPatientZone
    )
    patientRepository.saveOngoingEntry(ongoingPatientEntry).blockingAwait()

    // when
    val savedPatient = patientRepository.saveOngoingEntryAsPatient(loggedInUser, facilityToSavePatientAt).blockingGet()
    val savedPatientAddress = patientRepository.address(savedPatient.addressUuid).blockingFirst().toNullable()!!

    // then
    val expectedPatientAddress = testData.patientAddress(
        uuid = savedPatient.addressUuid,
        streetAddress = userEnteredPatientStreetAddress,
        colonyOrVillage = userEnteredPatientColony,
        district = userEnteredPatientDistrict,
        zone = userEnteredPatientZone,
        state = userEnteredPatientState,
        country = facilityCountry,
        createdAt = Instant.now(clock),
        updatedAt = Instant.now(clock),
        deletedAt = null
    )
    assertThat(savedPatientAddress).isEqualTo(expectedPatientAddress)
  }

  @Test
  fun querying_whether_blood_pressures_for_patient_have_change_should_work_as_expected() {
    fun setBpSyncStatusToDone(bpUuid: UUID) {
      database.bloodPressureDao().updateSyncStatus(listOf(bpUuid), DONE)
    }

    val patientUuid = UUID.randomUUID()
    val now = Instant.now(clock)
    val oneSecondEarlier = now.minus(Duration.ofSeconds(1))
    val fiftyNineSecondsLater = now.plus(Duration.ofSeconds(59))
    val oneMinuteLater = now.plus(Duration.ofMinutes(1))

    val bp1ForPatient = testData.bloodPressureMeasurement(
        patientUuid = patientUuid,
        syncStatus = PENDING,
        updatedAt = now
    )
    val bp2ForPatient = testData.bloodPressureMeasurement(
        patientUuid = patientUuid,
        syncStatus = PENDING,
        updatedAt = oneMinuteLater
    )
    val bpForSomeOtherPatient = testData.bloodPressureMeasurement(
        patientUuid = UUID.randomUUID(),
        syncStatus = PENDING,
        updatedAt = now
    )

    database.bloodPressureDao().save(listOf(bp1ForPatient, bp2ForPatient, bpForSomeOtherPatient))

    assertThat(patientRepository.haveBpsForPatientChangedSince(patientUuid, oneSecondEarlier)).isTrue()
    assertThat(patientRepository.haveBpsForPatientChangedSince(patientUuid, now)).isTrue()
    assertThat(patientRepository.haveBpsForPatientChangedSince(patientUuid, fiftyNineSecondsLater)).isTrue()
    assertThat(patientRepository.haveBpsForPatientChangedSince(patientUuid, oneMinuteLater)).isFalse()

    setBpSyncStatusToDone(bp2ForPatient.uuid)
    assertThat(patientRepository.haveBpsForPatientChangedSince(patientUuid, fiftyNineSecondsLater)).isFalse()
    assertThat(patientRepository.haveBpsForPatientChangedSince(patientUuid, oneSecondEarlier)).isTrue()

    setBpSyncStatusToDone(bp1ForPatient.uuid)
    assertThat(patientRepository.haveBpsForPatientChangedSince(patientUuid, oneSecondEarlier)).isFalse()
    assertThat(patientRepository.haveBpsForPatientChangedSince(bpForSomeOtherPatient.patientUuid, oneSecondEarlier)).isTrue()

    setBpSyncStatusToDone(bpForSomeOtherPatient.uuid)
    assertThat(patientRepository.haveBpsForPatientChangedSince(bpForSomeOtherPatient.patientUuid, oneSecondEarlier)).isFalse()
  }

  @Test
  fun querying_whether_blood_sugars_for_patient_have_changed_should_work_as_expected() {
    fun setBloodSugarSyncStatusToDone(bloodSugarUuid: UUID) {
      database.bloodSugarDao().updateSyncStatus(listOf(bloodSugarUuid), DONE)
    }

    val patientUuid = UUID.fromString("8ef781b9-afd3-4bb7-8742-193e48471f09")
    val now = Instant.now(clock)
    val oneSecondEarlier = now.minus(Duration.ofSeconds(1))
    val fiftyNineSecondsLater = now.plus(Duration.ofSeconds(59))
    val oneMinuteLater = now.plus(Duration.ofMinutes(1))

    val bloodSugar1ForPatient = testData.bloodSugarMeasurement(
        patientUuid = patientUuid,
        syncStatus = PENDING,
        updatedAt = now
    )
    val bloodSugar2ForPatient = testData.bloodSugarMeasurement(
        patientUuid = patientUuid,
        syncStatus = PENDING,
        updatedAt = oneMinuteLater
    )
    val bloodSugarForSomeOtherPatient = testData.bloodSugarMeasurement(
        patientUuid = UUID.fromString("ba774e35-b727-472b-bb9c-231af252d190"),
        syncStatus = PENDING,
        updatedAt = now
    )

    database.bloodSugarDao().save(listOf(bloodSugar1ForPatient, bloodSugar2ForPatient, bloodSugarForSomeOtherPatient))

    assertThat(patientRepository.haveBloodSugarsForPatientChangedSince(patientUuid, oneSecondEarlier)).isTrue()
    assertThat(patientRepository.haveBloodSugarsForPatientChangedSince(patientUuid, now)).isTrue()
    assertThat(patientRepository.haveBloodSugarsForPatientChangedSince(patientUuid, fiftyNineSecondsLater)).isTrue()
    assertThat(patientRepository.haveBloodSugarsForPatientChangedSince(patientUuid, oneMinuteLater)).isFalse()

    setBloodSugarSyncStatusToDone(bloodSugar2ForPatient.uuid)
    assertThat(patientRepository.haveBloodSugarsForPatientChangedSince(patientUuid, fiftyNineSecondsLater)).isFalse()
    assertThat(patientRepository.haveBloodSugarsForPatientChangedSince(patientUuid, oneSecondEarlier)).isTrue()

    setBloodSugarSyncStatusToDone(bloodSugar1ForPatient.uuid)
    assertThat(patientRepository.haveBloodSugarsForPatientChangedSince(patientUuid, oneSecondEarlier)).isFalse()
    assertThat(patientRepository.haveBloodSugarsForPatientChangedSince(bloodSugarForSomeOtherPatient.patientUuid, oneSecondEarlier)).isTrue()

    setBloodSugarSyncStatusToDone(bloodSugarForSomeOtherPatient.uuid)
    assertThat(patientRepository.haveBloodSugarsForPatientChangedSince(bloodSugarForSomeOtherPatient.patientUuid, oneSecondEarlier)).isFalse()
  }

  @Test
  fun querying_whether_prescription_for_patient_has_changed_should_work_as_expected() {
    fun setPrescribedDrugSyncStatusToDone(prescribedDrug: UUID) {
      database.prescriptionDao().updateSyncStatus(listOf(prescribedDrug), DONE)
    }

    val patientUuid = UUID.randomUUID()
    val now = Instant.now(clock)
    val oneSecondEarlier = now.minus(Duration.ofSeconds(1))
    val fiftyNineSecondsLater = now.plus(Duration.ofSeconds(59))
    val oneMinuteLater = now.plus(Duration.ofMinutes(1))

    val prescribedDrug1ForPatient = testData.prescription(
        patientUuid = patientUuid,
        syncStatus = PENDING,
        updatedAt = now
    )
    val prescribedDrug2ForPatient = testData.prescription(
        patientUuid = patientUuid,
        syncStatus = PENDING,
        updatedAt = oneMinuteLater
    )
    val prescribedDrugForSomeOtherPatient = testData.prescription(
        patientUuid = UUID.randomUUID(),
        syncStatus = PENDING,
        updatedAt = now
    )

    database.prescriptionDao().save(listOf(prescribedDrug1ForPatient, prescribedDrug2ForPatient, prescribedDrugForSomeOtherPatient))

    assertThat(patientRepository.hasPrescriptionForPatientChangedSince(patientUuid, oneSecondEarlier)).isTrue()
    assertThat(patientRepository.hasPrescriptionForPatientChangedSince(patientUuid, now)).isTrue()
    assertThat(patientRepository.hasPrescriptionForPatientChangedSince(patientUuid, fiftyNineSecondsLater)).isTrue()
    assertThat(patientRepository.hasPrescriptionForPatientChangedSince(patientUuid, oneMinuteLater)).isFalse()

    setPrescribedDrugSyncStatusToDone(prescribedDrug2ForPatient.uuid)
    assertThat(patientRepository.hasPrescriptionForPatientChangedSince(patientUuid, fiftyNineSecondsLater)).isFalse()
    assertThat(patientRepository.hasPrescriptionForPatientChangedSince(patientUuid, oneSecondEarlier)).isTrue()

    setPrescribedDrugSyncStatusToDone(prescribedDrug1ForPatient.uuid)
    assertThat(patientRepository.hasPrescriptionForPatientChangedSince(patientUuid, oneSecondEarlier)).isFalse()
    assertThat(patientRepository.hasPrescriptionForPatientChangedSince(prescribedDrugForSomeOtherPatient.patientUuid, oneSecondEarlier)).isTrue()

    setPrescribedDrugSyncStatusToDone(prescribedDrugForSomeOtherPatient.uuid)
    assertThat(patientRepository.hasPrescriptionForPatientChangedSince(prescribedDrugForSomeOtherPatient.patientUuid, oneSecondEarlier)).isFalse()
  }

  @Test
  fun querying_whether_medical_history_for_patient_has_changed_should_work_as_expected() {
    fun setMedicalHistorySyncStatusToDone(medicalHistoryUuid: UUID) {
      database.medicalHistoryDao().updateSyncStatus(listOf(medicalHistoryUuid), DONE)
    }

    val patientUuid = UUID.randomUUID()
    val now = Instant.now(clock)
    val oneSecondEarlier = now.minus(Duration.ofSeconds(1))
    val fiftyNineSecondsLater = now.plus(Duration.ofSeconds(59))
    val oneMinuteLater = now.plus(Duration.ofMinutes(1))

    val medicalHistory1ForPatient = testData.medicalHistory(
        patientUuid = patientUuid,
        syncStatus = PENDING,
        updatedAt = now
    )
    val medicalHistory2ForPatient = testData.medicalHistory(
        patientUuid = patientUuid,
        syncStatus = PENDING,
        updatedAt = oneMinuteLater
    )
    val medicalHistoryForSomeOtherPatient = testData.medicalHistory(
        patientUuid = UUID.randomUUID(),
        syncStatus = PENDING,
        updatedAt = now
    )

    database.medicalHistoryDao().save(listOf(medicalHistory1ForPatient, medicalHistory2ForPatient, medicalHistoryForSomeOtherPatient))

    assertThat(patientRepository.hasMedicalHistoryForPatientChangedSince(patientUuid, oneSecondEarlier)).isTrue()
    assertThat(patientRepository.hasMedicalHistoryForPatientChangedSince(patientUuid, now)).isTrue()
    assertThat(patientRepository.hasMedicalHistoryForPatientChangedSince(patientUuid, fiftyNineSecondsLater)).isTrue()
    assertThat(patientRepository.hasMedicalHistoryForPatientChangedSince(patientUuid, oneMinuteLater)).isFalse()

    setMedicalHistorySyncStatusToDone(medicalHistory2ForPatient.uuid)
    assertThat(patientRepository.hasMedicalHistoryForPatientChangedSince(patientUuid, fiftyNineSecondsLater)).isFalse()
    assertThat(patientRepository.hasMedicalHistoryForPatientChangedSince(patientUuid, oneSecondEarlier)).isTrue()

    setMedicalHistorySyncStatusToDone(medicalHistory1ForPatient.uuid)
    assertThat(patientRepository.hasMedicalHistoryForPatientChangedSince(patientUuid, oneSecondEarlier)).isFalse()
    assertThat(patientRepository.hasMedicalHistoryForPatientChangedSince(medicalHistoryForSomeOtherPatient.patientUuid, oneSecondEarlier)).isTrue()

    setMedicalHistorySyncStatusToDone(medicalHistoryForSomeOtherPatient.uuid)
    assertThat(patientRepository.hasMedicalHistoryForPatientChangedSince(medicalHistoryForSomeOtherPatient.patientUuid, oneSecondEarlier)).isFalse()
  }

  @Test
  fun when_patient_address_details_are_saved_then_all_patient_address_fields_should_be_retrieved_as_expected() {
    val patientUuid = UUID.fromString("a0ac466f-5a8d-436b-8845-4412e7f86cdd")
    val patientAddressUuid = UUID.fromString("a0ac466f-5a8d-436b-8845-4412e7f86cdd")
    val streetAddress = "Streetz"
    val zone = "Zonez"

    val patientProfile = testData.patientProfile(
        patientUuid = patientUuid,
        patientAddressUuid = patientAddressUuid,
        syncStatus = PENDING
    ).run {
      copy(address = testData.patientAddress(
          uuid = patientAddressUuid,
          streetAddress = streetAddress,
          zone = zone
      ))
    }
    patientRepository.save(listOf(patientProfile)).blockingAwait()

    val patients = patientRepository.recordsWithSyncStatus(PENDING).blockingGet()
    assertThat(patients.size).isEqualTo(1)

    assertThat(patients.first().address.streetAddress).isEqualTo(streetAddress)
    assertThat(patients.first().address.zone).isEqualTo(zone)
  }

  @Test
  fun loading_patient_records_with_pending_status_should_work_correctly() {
    // given
    val patientUuid = UUID.fromString("894e6fed-4f0f-4c4b-9537-4284cdc0ae63")
    val patientAddressUuid = UUID.fromString("1d4aaa08-ca1e-4690-b9fe-6f2f043d0859")
    val patient = TestData.patient(
        uuid = patientUuid,
        addressUuid = patientAddressUuid,
        status = Active,
        createdAt = Instant.now(clock),
        updatedAt = Instant.now(clock),
        deletedAt = Instant.now(clock),
        deletedReason = DeletedReason.AccidentalRegistration,
        syncStatus = PENDING
    )
    val patientAddress = TestData.patientAddress(
        uuid = patientAddressUuid
    )

    val patientProfile = TestData.patientProfile(
        patientUuid = patientUuid
    ).run {
      copy(
          patient = patient,
          address = patientAddress
      )
    }

    patientRepository.save(listOf(patientProfile)).blockingAwait()

    // when
    val patients = patientRepository.recordsWithSyncStatus(PENDING).blockingGet()
    assertThat(patients.first()).isEqualTo(patientProfile)
  }

  @Test
  fun deleting_Bangladesh_Id_should_mark_it_as_soft_deleted() {
    //given
    val now = Instant.now(clock)
    val patientUuid = UUID.fromString("e5b785f5-3128-470c-9b8f-466557dc3ff6")
    val patientProfile = testData.patientProfile(patientUuid = patientUuid, syncStatus = DONE, generateBusinessId = false)
    val bangladeshNationalId = testData.businessId(
        uuid = UUID.fromString("8c32b6ba-c77f-4e9f-b32c-ed79d363edb1"),
        patientUuid = patientProfile.patientUuid,
        identifier = Identifier("12491236-9725-4eff-aab9-ba789ba37d72", BangladeshNationalId),
        createdAt = now
    )
    patientRepository.save(listOf(patientProfile.copy(businessIds = listOf(bangladeshNationalId)))).blockingAwait()

    //when
    clock.advanceBy(Duration.ofHours(1))
    patientRepository.deleteBusinessId(bangladeshNationalId).blockingAwait()

    //then
    val businessId = database
        .businessIdDao()
        .allBusinessIdsWithType(BangladeshNationalId)
        .blockingGet()
        .first { it.patientUuid == patientUuid }

    assertThat(businessId).isNotNull()
    with(businessId) {
      val oneHourLater = Instant.now(clock)
      assertThat(deletedAt).isEqualTo(oneHourLater)
      assertThat(updatedAt).isEqualTo(oneHourLater)
    }
  }

  @Test
  fun fetching_the_patient_profile_should_load_the_patient_details() {
    // given
    val patientDao = database.patientDao()
    val patientAddressDao = database.addressDao()

    val patientAddress = testData.patientAddress(uuid = UUID.fromString("b0a989ce-0298-4fdd-9c7d-074d36ddb5ad"))
    val patient = testData.patient(
        uuid = UUID.fromString("b0547622-96a2-48f9-bd41-bd86cbd2dee2"),
        addressUuid = patientAddress.uuid
    )

    patientAddressDao.save(patientAddress)
    patientDao.save(patient)

    // when
    val patientProfile = patientRepository.patientProfileImmediate(patient.uuid) as Just<PatientProfile>

    // then
    assertThat(patientProfile.value.patient).isEqualTo(patient)
    assertThat(patientProfile.value.address).isEqualTo(patientAddress)
    assertThat(patientProfile.value.phoneNumbers).isEmpty()
    assertThat(patientProfile.value.businessIds).isEmpty()
  }

  @Test
  fun fetching_the_patient_profile_should_load_the_patient_phone_numbers() {
    // given
    val patientDao = database.patientDao()
    val patientAddressDao = database.addressDao()
    val patientPhoneNumberDao = database.phoneNumberDao()

    val patientAddress = testData.patientAddress(uuid = UUID.fromString("b0a989ce-0298-4fdd-9c7d-074d36ddb5ad"))
    val patient = testData.patient(
        uuid = UUID.fromString("b0547622-96a2-48f9-bd41-bd86cbd2dee2"),
        addressUuid = patientAddress.uuid
    )
    val phoneNumbers = listOf(
        testData.patientPhoneNumber(
            uuid = UUID.fromString("5dd1909a-2e08-4287-8858-2d2d8689bda0"),
            patientUuid = patient.uuid,
            number = "1234567890"
        ),
        testData.patientPhoneNumber(
            uuid = UUID.fromString("1770d775-bf43-40e9-a134-33492f90f751"),
            patientUuid = patient.uuid,
            number = "0987654321"
        )
    )
    patientAddressDao.save(patientAddress)
    patientDao.save(patient)
    patientPhoneNumberDao.save(phoneNumbers)

    // when
    val patientProfile = patientRepository.patientProfileImmediate(patient.uuid) as Just<PatientProfile>

    // then
    assertThat(patientProfile.value.patient).isEqualTo(patient)
    assertThat(patientProfile.value.address).isEqualTo(patientAddress)
    assertThat(patientProfile.value.phoneNumbers).containsExactlyElementsIn(phoneNumbers)
    assertThat(patientProfile.value.businessIds).isEmpty()
  }

  @Test
  fun fetching_the_patient_profile_should_load_the_business_ids() {
    // given
    val patientDao = database.patientDao()
    val patientAddressDao = database.addressDao()
    val businessIdDao = database.businessIdDao()

    val patientAddress = testData.patientAddress(uuid = UUID.fromString("b0a989ce-0298-4fdd-9c7d-074d36ddb5ad"))
    val patient = testData.patient(
        uuid = UUID.fromString("b0547622-96a2-48f9-bd41-bd86cbd2dee2"),
        addressUuid = patientAddress.uuid
    )
    val businessIds = listOf(
        testData.businessId(
            uuid = UUID.fromString("ab909199-6f8c-4b2f-856d-021f5b2aca81"),
            identifier = Identifier(value = "05ab1305-0d42-424d-8bea-04432df09897", type = BpPassport),
            patientUuid = patient.uuid
        ),
        testData.businessId(
            uuid = UUID.fromString("62d76ed6-5714-4b80-830f-0bd031c4d318"),
            identifier = Identifier(value = "1234-5678-0986-5432", type = BangladeshNationalId),
            patientUuid = patient.uuid
        )
    )
    patientAddressDao.save(patientAddress)
    patientDao.save(patient)
    businessIdDao.save(businessIds)

    // when
    val patientProfile = patientRepository.patientProfileImmediate(patient.uuid) as Just<PatientProfile>

    // then
    assertThat(patientProfile.value.patient).isEqualTo(patient)
    assertThat(patientProfile.value.address).isEqualTo(patientAddress)
    assertThat(patientProfile.value.phoneNumbers).isEmpty()
    assertThat(patientProfile.value.businessIds).containsExactlyElementsIn(businessIds)
  }

  @Test
  fun subscribing_to_patient_profile_changes_should_load_the_patient_details() {
    // given
    val patientDao = database.patientDao()
    val patientAddressDao = database.addressDao()

    val patientAddress = testData.patientAddress(uuid = UUID.fromString("b0a989ce-0298-4fdd-9c7d-074d36ddb5ad"))
    val patient = testData.patient(
        uuid = UUID.fromString("b0547622-96a2-48f9-bd41-bd86cbd2dee2"),
        addressUuid = patientAddress.uuid
    )

    patientAddressDao.save(patientAddress)
    patientDao.save(patient)

    // when
    val patientProfile = patientRepository.patientProfile(patient.uuid).blockingFirst() as Just<PatientProfile>

    // then
    assertThat(patientProfile.value.patient).isEqualTo(patient)
    assertThat(patientProfile.value.address).isEqualTo(patientAddress)
    assertThat(patientProfile.value.phoneNumbers).isEmpty()
    assertThat(patientProfile.value.businessIds).isEmpty()
  }

  @Test
  fun subscribing_to_patient_profile_changes_should_load_the_patient_phone_numbers() {
    // given
    val patientDao = database.patientDao()
    val patientAddressDao = database.addressDao()
    val patientPhoneNumberDao = database.phoneNumberDao()

    val patientAddress = testData.patientAddress(uuid = UUID.fromString("b0a989ce-0298-4fdd-9c7d-074d36ddb5ad"))
    val patient = testData.patient(
        uuid = UUID.fromString("b0547622-96a2-48f9-bd41-bd86cbd2dee2"),
        addressUuid = patientAddress.uuid
    )
    val phoneNumbers = listOf(
        testData.patientPhoneNumber(
            uuid = UUID.fromString("5dd1909a-2e08-4287-8858-2d2d8689bda0"),
            patientUuid = patient.uuid,
            number = "1234567890"
        ),
        testData.patientPhoneNumber(
            uuid = UUID.fromString("1770d775-bf43-40e9-a134-33492f90f751"),
            patientUuid = patient.uuid,
            number = "0987654321"
        )
    )
    patientAddressDao.save(patientAddress)
    patientDao.save(patient)
    patientPhoneNumberDao.save(phoneNumbers)

    // when
    val patientProfile = patientRepository.patientProfile(patient.uuid).blockingFirst() as Just<PatientProfile>

    // then
    assertThat(patientProfile.value.patient).isEqualTo(patient)
    assertThat(patientProfile.value.address).isEqualTo(patientAddress)
    assertThat(patientProfile.value.phoneNumbers).containsExactlyElementsIn(phoneNumbers)
    assertThat(patientProfile.value.businessIds).isEmpty()
  }

  @Test
  fun subscribing_to_patient_profile_changes_should_load_the_business_ids() {
    // given
    val patientDao = database.patientDao()
    val patientAddressDao = database.addressDao()
    val businessIdDao = database.businessIdDao()

    val patientAddress = testData.patientAddress(uuid = UUID.fromString("b0a989ce-0298-4fdd-9c7d-074d36ddb5ad"))
    val patient = testData.patient(
        uuid = UUID.fromString("b0547622-96a2-48f9-bd41-bd86cbd2dee2"),
        addressUuid = patientAddress.uuid
    )
    val businessIds = listOf(
        testData.businessId(
            uuid = UUID.fromString("ab909199-6f8c-4b2f-856d-021f5b2aca81"),
            identifier = Identifier(value = "05ab1305-0d42-424d-8bea-04432df09897", type = BpPassport),
            patientUuid = patient.uuid
        ),
        testData.businessId(
            uuid = UUID.fromString("62d76ed6-5714-4b80-830f-0bd031c4d318"),
            identifier = Identifier(value = "1234-5678-0986-5432", type = BangladeshNationalId),
            patientUuid = patient.uuid
        )
    )
    patientAddressDao.save(patientAddress)
    patientDao.save(patient)
    businessIdDao.save(businessIds)

    // when
    val patientProfile = patientRepository.patientProfile(patient.uuid).blockingFirst() as Just<PatientProfile>

    // then
    assertThat(patientProfile.value.patient).isEqualTo(patient)
    assertThat(patientProfile.value.address).isEqualTo(patientAddress)
    assertThat(patientProfile.value.phoneNumbers).isEmpty()
    assertThat(patientProfile.value.businessIds).containsExactlyElementsIn(businessIds)
  }

  @Test
  fun deleting_patient_should_work_as_expected() {
    // given
    val now = Instant.now(clock)
    val patientUuid = UUID.fromString("d5c41c17-ff61-4c06-8499-ab2d3bf4e9e7")
    val patientProfile = TestData.patientProfile(
        patientUuid = patientUuid,
        patientCreatedAt = now,
        patientUpdatedAt = now
    )
    val expectedDeletedReason = DeletedReason.Duplicate

    patientRepository.save(listOf(patientProfile)).blockingAwait()

    // when
    clock.advanceBy(Duration.ofHours(1))
    patientRepository.deletePatient(patientUuid = patientUuid, deletedReason = expectedDeletedReason)

    // then
    val deletedPatient = patientRepository.patientImmediate(patientUuid)!!
    val oneHourLater = Instant.now(clock)

    with(deletedPatient) {
      assertThat(createdAt).isEqualTo(now)
      assertThat(updatedAt).isEqualTo(oneHourLater)
      assertThat(deletedAt).isEqualTo(oneHourLater)
      assertThat(deletedReason).isEqualTo(expectedDeletedReason)
      assertThat(syncStatus).isEqualTo(PENDING)
    }
  }
}
