package io.github.thgillwtnorizoh.modesty

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)

            addView(TextView(context).apply {
                text = "Modesty"
                textSize = 30f
                gravity = Gravity.CENTER
            })

            addView(TextView(context).apply {
                text = "Foundation brick 1\nOne editor core. Many small doors."
                textSize = 16f
                gravity = Gravity.CENTER
            })
        }

        setContentView(content)
    }
}
