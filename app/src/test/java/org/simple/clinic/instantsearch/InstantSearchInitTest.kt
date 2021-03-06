package org.simple.clinic.instantsearch

import com.spotify.mobius.test.FirstMatchers.hasEffects
import com.spotify.mobius.test.FirstMatchers.hasModel
import com.spotify.mobius.test.InitSpec
import com.spotify.mobius.test.InitSpec.assertThatFirst
import org.junit.Test
import org.simple.clinic.TestData
import org.simple.clinic.patient.businessid.Identifier.IdentifierType.BpPassport
import org.simple.clinic.util.matchers.IterableNotContaining.Companion.doesNotContain
import java.util.UUID

class InstantSearchInitTest {

  private val initSpec = InitSpec(InstantSearchInit())
  private val identifier = TestData.identifier(
      value = "f16ebf24-14fb-46c7-9b34-b49cdc1c9453",
      type = BpPassport
  )
  private val defaultModel = InstantSearchModel.create(identifier)

  @Test
  fun `when screen is created, then load current facility`() {
    val model = InstantSearchModel.create(additionalIdentifier = null)

    initSpec
        .whenInit(model)
        .then(assertThatFirst(
            hasModel(model),
            hasEffects(LoadCurrentFacility)
        ))
  }

  @Test
  fun `when screen is opened from blank bp passport scan, then load current facility and open bp passport sheet`() {
    initSpec
        .whenInit(defaultModel)
        .then(assertThatFirst(
            hasModel(defaultModel.bpPassportSheetOpened()),
            hasEffects(LoadCurrentFacility, OpenBpPassportSheet(identifier))
        ))
  }


  @Test
  fun `when screen is restored and facility is loaded, then validate search query`() {
    val facility = TestData.facility(
        uuid = UUID.fromString("df98a72b-3392-4364-80b3-c73328bafed3"),
        name = "PHC Obvious"
    )
    val facilityLoadedModel = defaultModel
        .bpPassportSheetOpened()
        .facilityLoaded(facility)
        .searchQueryChanged("Pa")

    initSpec
        .whenInit(facilityLoadedModel)
        .then(assertThatFirst(
            hasModel(facilityLoadedModel),
            hasEffects(ValidateSearchQuery("Pa"))
        ))
  }

  @Test
  fun `when screen is restored after receiving the BP Passport sheet result, then do not open bp passport sheet`() {
    val model = defaultModel.bpPassportSheetOpened()

    initSpec
        .whenInit(model)
        .then(assertThatFirst(
            hasModel(model),
            hasEffects(doesNotContain(OpenBpPassportSheet(identifier)))
        ))
  }
}
