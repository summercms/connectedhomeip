/*
 *   Copyright (c) 2020 Project CHIP Authors
 *   All rights reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package com.google.chip.chiptool

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import chip.setuppayload.SetupPayloadParser.UnrecognizedQrCodeException
import com.google.chip.chiptool.attestation.AttestationTestFragment
import com.google.chip.chiptool.clusterclient.OnOffClientFragment
import com.google.chip.chiptool.commissioner.CommissionerActivity
import com.google.chip.chiptool.echoclient.EchoClientFragment
import com.google.chip.chiptool.provisioning.DeviceProvisioningFragment
import com.google.chip.chiptool.provisioning.ProvisionNetworkType
import com.google.chip.chiptool.setuppayloadscanner.BarcodeFragment
import com.google.chip.chiptool.setuppayloadscanner.CHIPDeviceDetailsFragment
import com.google.chip.chiptool.setuppayloadscanner.CHIPDeviceInfo
import com.google.chip.chiptool.setuppayloadscanner.QrCodeInfo
import chip.devicecontroller.KeyValueStoreManager
import chip.devicecontroller.PersistentStorage
import chip.setuppayload.SetupPayload
import chip.setuppayload.SetupPayloadParser

class CHIPToolActivity :
    AppCompatActivity(),
    BarcodeFragment.Callback,
    SelectActionFragment.Callback,
    DeviceProvisioningFragment.Callback {

  private var networkType: ProvisionNetworkType? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.top_activity)

    PersistentStorage.initialize(this);
    KeyValueStoreManager.initialize(this);

    if (savedInstanceState == null) {
      val fragment = SelectActionFragment.newInstance()
      supportFragmentManager
          .beginTransaction()
          .add(R.id.fragment_container, fragment, fragment.javaClass.simpleName)
          .commit()
    } else {
      networkType =
          ProvisionNetworkType.fromName(savedInstanceState.getString(ARG_PROVISION_NETWORK_TYPE))
    }

    if (intent?.action == NfcAdapter.ACTION_NDEF_DISCOVERED)
      onNfcIntent(intent)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putString(ARG_PROVISION_NETWORK_TYPE, networkType?.name)

    super.onSaveInstanceState(outState)
  }

  override fun onCHIPDeviceInfoReceived(deviceInfo: CHIPDeviceInfo) {
    if (networkType == null) {
      showFragment(CHIPDeviceDetailsFragment.newInstance(deviceInfo))
    } else {
      showFragment(DeviceProvisioningFragment.newInstance(deviceInfo, networkType!!), false)
    }
  }

  override fun onPairingComplete() {
    showFragment(OnOffClientFragment.newInstance(), false)
  }

  override fun handleScanQrCodeClicked() {
    showFragment(BarcodeFragment.newInstance())
  }

  override fun onProvisionWifiCredentialsClicked() {
    networkType = ProvisionNetworkType.WIFI
    showFragment(BarcodeFragment.newInstance(), false)
  }

  override fun onProvisionThreadCredentialsClicked() {
    networkType = ProvisionNetworkType.THREAD
    showFragment(BarcodeFragment.newInstance(), false)
  }

  override fun handleCommissioningClicked() {
    var intent = Intent(this, CommissionerActivity::class.java)
    startActivityForResult(intent, REQUEST_CODE_COMMISSIONING)
  }

  override fun handleEchoClientClicked() {
    showFragment(EchoClientFragment.newInstance())
  }

  override fun handleOnOffClicked() {
    showFragment(OnOffClientFragment.newInstance())
  }

  override fun handleAttestationTestClicked() {
    showFragment(AttestationTestFragment.newInstance())
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)

    if (requestCode == REQUEST_CODE_COMMISSIONING) {
      // Simply ignore the commissioning result.
      // TODO: tracking commissioned devices.
    }
  }

  private fun showFragment(fragment: Fragment, showOnBack: Boolean = true) {
    val fragmentTransaction = supportFragmentManager
        .beginTransaction()
        .replace(R.id.fragment_container, fragment, fragment.javaClass.simpleName)

    if (showOnBack) {
      fragmentTransaction.addToBackStack(null)
    }

    fragmentTransaction.commit()
  }

  private fun onNfcIntent(intent: Intent?) {
    // Require 1 NDEF message containing 1 NDEF record
    val messages = intent?.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
    if (messages?.size != 1) return

    val records = (messages[0] as NdefMessage).records
    if (records.size != 1) return

    // Require NDEF URI record starting with "ch:"
    val uri = records[0].toUri()
    if (!uri?.scheme.equals("ch", true)) return

    lateinit var setupPayload: SetupPayload
    try {
      // TODO: Issue #4504 - Remove replacing _ with spaces after problem described in #415 will be fixed.
      setupPayload =
        SetupPayloadParser().parseQrCode(uri.toString().toUpperCase().replace('_', ' '))
    } catch (ex: UnrecognizedQrCodeException) {
      Log.e(TAG, "Unrecognized QR Code", ex)
      Toast.makeText(this, "Unrecognized QR Code", Toast.LENGTH_SHORT).show()
      return
    }

    val deviceInfo = CHIPDeviceInfo(
        setupPayload.version,
        setupPayload.vendorId,
        setupPayload.productId,
        setupPayload.discriminator,
        setupPayload.setupPinCode,
        setupPayload.optionalQRCodeInfo.mapValues { (_, info) -> QrCodeInfo(info.tag, info.type, info.data, info.int32) }
    )

    val buttons = arrayOf(
        getString(R.string.nfc_tag_action_show),
        getString(R.string.nfc_tag_action_wifi),
        getString(R.string.nfc_tag_action_thread))

    AlertDialog.Builder(this)
        .setTitle(R.string.nfc_tag_action_title)
        .setItems(buttons) { _, which ->
          this.networkType = when (which) {
            1 -> ProvisionNetworkType.WIFI
            2 -> ProvisionNetworkType.THREAD
            else -> null
          }
          onCHIPDeviceInfoReceived(deviceInfo)
        }
        .create()
        .show()
  }

  companion object {
    private const val TAG = "CHIPToolActivity"
    private const val ARG_PROVISION_NETWORK_TYPE = "provision_network_type"

    var REQUEST_CODE_COMMISSIONING = 0xB003
  }
}
