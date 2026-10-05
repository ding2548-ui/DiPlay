package com.shilapi.xcertplay.shared

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template

class MyCarAppScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        return MessageTemplate.Builder(carContext.getString(R.string.carapp_hw_not_configured))
            .setHeaderAction(Action.APP_ICON)
            .setTitle(carContext.getString(R.string.carapp_hw_status_title))
            .build()
    }
}
