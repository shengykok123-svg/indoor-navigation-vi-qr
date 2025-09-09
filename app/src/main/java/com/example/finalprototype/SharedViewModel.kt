package com.example.finalprototype

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SharedViewModel : ViewModel() {
    private val _destination = MutableLiveData("No destination selected")
    val destination: LiveData<String> = _destination

    fun setDestination(name: String) {
        _destination.value = name
    }
}
