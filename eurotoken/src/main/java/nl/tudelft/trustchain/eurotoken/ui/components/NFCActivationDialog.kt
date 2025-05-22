package nl.tudelft.trustchain.eurotoken.ui.components

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import nl.tudelft.trustchain.eurotoken.R

class NFCActivationDialog : DialogFragment() {

    interface NFCDialogListener {
        fun onCancel()
    }

    private var listener: NFCDialogListener? = null
    private var message: String = ""

    companion object {
        private const val ARG_MESSAGE = "message"

        fun newInstance(message: String): NFCActivationDialog {
            val dialog = NFCActivationDialog()
            val args = Bundle()
            args.putString(ARG_MESSAGE, message)
            dialog.arguments = args
            return dialog
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let { args ->
            message = args.getString(ARG_MESSAGE, "Hold phones together")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_simple_nfc, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val messageText: TextView = view.findViewById(R.id.nfc_message)
        val cancelButton: Button = view.findViewById(R.id.nfc_cancel)

        messageText.text = message

        cancelButton.setOnClickListener {
            listener?.onCancel()
            dismiss()
        }
    }

    fun setListener(listener: NFCDialogListener) {
        this.listener = listener
    }

    fun updateMessage(newMessage: String) {
        view?.let { v ->
            val messageText: TextView = v.findViewById(R.id.nfc_message)
            messageText.text = newMessage
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }
}
