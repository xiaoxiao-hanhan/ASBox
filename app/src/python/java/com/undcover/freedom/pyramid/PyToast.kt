package com.undcover.freedom.pyramid

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

class PyToast {
    companion object {
        private var innerToast: Toast? = null
        private var mContext: Context? = null
        private var sInstance: PyToast? = null

        @JvmStatic
        fun init(context: Context) {
            mContext = context
        }

        @JvmStatic
        fun getInstance(): PyToast {
            if (sInstance == null) {
                synchronized(PyToast::class.java) {
                    if (sInstance == null) {
                        sInstance = PyToast()
                    }
                }
            }
            return requireNotNull(sInstance)
        }

        @JvmStatic
        fun showCancelableToast(msg: String?) {
            showCancelableToast(msg, Toast.LENGTH_SHORT)
        }

        @JvmStatic
        fun showCancelableToast(msg: String?, duration: Int) {
            val ctx = mContext ?: return
            Handler(Looper.getMainLooper()).post {
                try {
                    innerToast?.cancel()
                    val toast = Toast.makeText(ctx, msg, duration)
                    innerToast = toast
                    toast.show()
                } catch (ignored: Throwable) {
                }
            }
        }

        @JvmStatic
        fun showMessage(msg: String?, duration: Int) {
            val ctx = mContext ?: return
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(ctx, msg, duration).show()
                } catch (ignored: Throwable) {
                }
            }
        }
    }
}
