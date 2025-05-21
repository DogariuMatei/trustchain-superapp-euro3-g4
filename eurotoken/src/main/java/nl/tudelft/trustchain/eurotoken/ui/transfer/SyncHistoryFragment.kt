package nl.tudelft.trustchain.eurotoken.ui.transfer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import nl.tudelft.trustchain.eurotoken.R
import androidx.annotation.CallSuper
import androidx.appcompat.app.AlertDialog
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import kotlin.text.Charsets.UTF_8
import com.google.android.gms.nearby.connection.Strategy
import java.util.Random
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.ViewGroup
import nl.tudelft.trustchain.eurotoken.databinding.FragmentSyncHistoryBinding
import nl.tudelft.trustchain.eurotoken.ui.EurotokenBaseFragment
import androidx.core.content.ContextCompat

private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

class SyncHistoryFragment : EurotokenBaseFragment(R.layout.fragment_sync_history) {
    internal object CodenameGenerator {
        private val generator = Random()

        fun generate(): String {
            return "${generator.nextInt(100)}"
        }
    }

    private val STRATEGY = Strategy.P2P_POINT_TO_POINT
    private lateinit var connectionsClient: ConnectionsClient
    private val REQUEST_CODE_REQUIRED_PERMISSIONS = 1
    private var peerName: String? = null
    private var peerEndpointId: String? = null
    private var peerHistory: String? = null
    private var publicKey: String = CodenameGenerator.generate()
    private var _binding: FragmentSyncHistoryBinding? = null
    private val binding get() = _binding!!

    private var param1: String? = null
    private var param2: String? = null
//    private val navArgs: SyncHistoryFragmentArgs by navArgs()

    private val payloadCallback: PayloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let {
                    peerHistory = String(it, UTF_8)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS
                && peerHistory != null) {
                val ph = peerHistory!!
                binding.status.text = "Received ${ph}"
                peerHistory = null
                setSyncEnabled(true)
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            AlertDialog.Builder(requireContext())
                .setTitle("Accept connection to ${info.endpointName}")
                .setMessage("Confirm the code matches on both devices: ${info.authenticationDigits}")
                .setPositiveButton("Accept") { _, _ ->
                    connectionsClient.acceptConnection(endpointId, payloadCallback)
                    peerName = "Peer\n(${info.endpointName})"
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    connectionsClient.rejectConnection(endpointId)
                }
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show()
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    connectionsClient.stopAdvertising()
                    connectionsClient.stopDiscovery()
                    peerEndpointId = endpointId
                    binding.peerName.text = peerName
                    binding.status.text = "Connected"
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                }
                ConnectionsStatusCodes.STATUS_ERROR -> {
                }
                else -> {
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            reset()
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            connectionsClient.requestConnection(publicKey, endpointId, connectionLifecycleCallback)
                .addOnSuccessListener {  }
                .addOnFailureListener {  }
        }

        override fun onEndpointLost(endpointId: String) {
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
//        val someInt = requireArguments().getInt("some_int")
//        val transactionArgs = navArgs.transactionArgs
        connectionsClient = Nearby.getConnectionsClient(requireContext())
        binding.myName.text = "You\n($publicKey)"
        binding.findPeer.setOnClickListener {
            startAdvertising()
            startDiscovery()
            binding.status.text = "Searching for peers..."
            binding.findPeer.visibility = View.GONE
            binding.disconnect.visibility = View.VISIBLE
        }
        binding.apply {
            sync.setOnClickListener { sendHistory() }
        }
        binding.disconnect.setOnClickListener {
            peerEndpointId?.let { connectionsClient.disconnectFromEndpoint(it) }
            reset()
        }
        reset()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSyncHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
//            param1 = it.getString(ARG_PARAM1)
//            param2 = it.getString(ARG_PARAM2)
        }
    }

    @CallSuper
    override fun onStart() { //shouldShowRequestPermissionRationale
        super.onStart()
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_CODE_REQUIRED_PERMISSIONS
            )
        }
    }

    @CallSuper
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val errMsg = "Cannot start without required permissions"
        if (requestCode == REQUEST_CODE_REQUIRED_PERMISSIONS) {
            grantResults.forEach {
                if (it == PackageManager.PERMISSION_DENIED) {
                    Toast.makeText(requireContext(), errMsg, Toast.LENGTH_LONG).show()
                    requireActivity().finish()
                    return
                }
            }
            requireActivity().recreate()
        }
    }

    private fun sendHistory() {
//        val bytesPayload = Payload.fromBytes(byteArrayOf(0xa, 0xb, 0xc, 0xd));
//        connectionsClient.sendPayload(peerEndpointId!!, bytesPayload);
        connectionsClient.sendPayload(
            peerEndpointId!!,
            Payload.fromBytes(CodenameGenerator.generate().toByteArray(UTF_8))
        )
        binding.status.text = "Sending history"
    }

    private fun setSyncEnabled(state: Boolean) {
        binding.apply {
            sync.isEnabled = state
        }
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            publicKey,
            requireActivity().packageName,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {  }
        .addOnFailureListener {  }
    }

    @CallSuper
    override fun onStop() {
        connectionsClient.apply {
            stopAdvertising()
            stopDiscovery()
            stopAllEndpoints()
        }
        reset()
        super.onStop()
    }

    private fun reset() {
        peerEndpointId = null
        peerName = null
        binding.disconnect.visibility = View.GONE
        binding.findPeer.visibility = View.VISIBLE
        setSyncEnabled(false)
        binding.peerName.text = "peer\n(none yet)"
        binding.status.text = "..."
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(requireActivity().packageName, endpointDiscoveryCallback, options)
            .addOnSuccessListener {  }
            .addOnFailureListener {  }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        @JvmStatic
        fun newInstance() = //param1: String, param2: String
            SyncHistoryFragment().apply {
                arguments = Bundle().apply {
//                    putString(ARG_PARAM1, param1)
//                    putString(ARG_PARAM2, param2)
                }
            }
    }
}
