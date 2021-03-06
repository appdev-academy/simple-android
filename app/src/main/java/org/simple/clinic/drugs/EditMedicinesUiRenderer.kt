package org.simple.clinic.drugs

import org.simple.clinic.drugs.EditMedicineButtonState.REFILL_MEDICINE
import org.simple.clinic.drugs.EditMedicineButtonState.SAVE_MEDICINE
import org.simple.clinic.drugs.selection.CustomPrescribedDrugListItem
import org.simple.clinic.drugs.selection.EditMedicinesUi
import org.simple.clinic.drugs.selection.ProtocolDrugListItem
import org.simple.clinic.mobius.ViewRenderer
import org.simple.clinic.protocol.ProtocolDrugAndDosages

class EditMedicinesUiRenderer(private val ui: EditMedicinesUi) : ViewRenderer<EditMedicinesModel> {

  override fun render(model: EditMedicinesModel) {
    if (model.prescribedDrugs != null && model.protocolDrugs != null)
      renderPrescribedProtocolDrugs(model, model.prescribedDrugs, model.protocolDrugs)
    when (model.editMedicineButtonState) {
      SAVE_MEDICINE -> {
        ui.showDoneButton()
        ui.hideRefillMedicineButton()
      }
      REFILL_MEDICINE -> {
        ui.showRefillMedicineButton()
        ui.hideDoneButton()
      }
    }
  }

  private fun renderPrescribedProtocolDrugs(
      model: EditMedicinesModel,
      prescribedDrugs: List<PrescribedDrug>,
      protocolDrugs: List<ProtocolDrugAndDosages>
  ) {
    val (prescribedProtocolDrugs, prescribedCustomDrugs) = prescribedDrugs.partition(model::isProtocolDrug)

    val protocolDrugSelectionItems = protocolDrugSelectionItems(protocolDrugs, prescribedProtocolDrugs)
    val customPrescribedDrugItems = customPrescribedDrugItems(prescribedCustomDrugs)
    val drugsList = (protocolDrugSelectionItems + customPrescribedDrugItems)
        .sortedByDescending { it.prescribedDrug?.updatedAt }

    ui.populateDrugsList(drugsList)
  }

  private fun customPrescribedDrugItems(
      prescribedCustomDrugs: List<PrescribedDrug>
  ): List<CustomPrescribedDrugListItem> {
    return prescribedCustomDrugs
        .map { prescribedDrug ->
          CustomPrescribedDrugListItem(
              prescribedDrug = prescribedDrug
          )
        }
  }

  private fun protocolDrugSelectionItems(
      protocolDrugs: List<ProtocolDrugAndDosages>,
      prescribedProtocolDrugs: List<PrescribedDrug>
  ): List<ProtocolDrugListItem> {
    // Show dosage if prescriptions exist for them.
    return protocolDrugs
        .mapIndexed { index: Int, drugAndDosages: ProtocolDrugAndDosages ->
          val matchingPrescribedDrug = prescribedProtocolDrugs.firstOrNull { it.name == drugAndDosages.drugName }
          ProtocolDrugListItem(
              id = index,
              drugName = drugAndDosages.drugName,
              prescribedDrug = matchingPrescribedDrug)
        }
  }
}
