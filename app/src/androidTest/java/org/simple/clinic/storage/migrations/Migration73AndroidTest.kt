package org.simple.clinic.storage.migrations

import org.junit.Test
import org.simple.clinic.assertColumns

class Migration73AndroidTest : BaseDatabaseMigrationTest(72, 73) {

  @Test
  fun `capabilities_should_be_added_to_the_user_table`() {
    before.assertColumns("LoggedInUser", setOf(
        "uuid",
        "fullName",
        "phoneNumber",
        "pinDigest",
        "status",
        "createdAt",
        "updatedAt",
        "loggedInStatus",
        "registrationFacilityUuid",
        "currentFacilityUuid",
        "teleconsultPhoneNumber"
    ))

    after.assertColumns("LoggedInUser", setOf(
        "uuid",
        "fullName",
        "phoneNumber",
        "pinDigest",
        "status",
        "createdAt",
        "updatedAt",
        "loggedInStatus",
        "registrationFacilityUuid",
        "currentFacilityUuid",
        "teleconsultPhoneNumber",
        "capability_canTeleconsult"
    ))
  }
}
