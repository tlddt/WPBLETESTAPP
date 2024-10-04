package com.example.wpbletestapp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private val MYTAG : String = "MyTag"
    private  val scanDuration: Long = 2500
    private val BLUETOOTH_CONNECT_PERMISSION_CODE = 1001
    private val BLUETOOTH_SCAN_PERMISSION_CODE = 1002
    private val ANDROID_12_PERMISSION_CODE = 1003
    private val ANDROID_6_PERMISSION_CODE = 1009

    private val myHandler = Handler(Looper.getMainLooper())
    private lateinit var runnable : Runnable

    private lateinit var enableBTlaunch : ActivityResultLauncher<Intent>
    //藍芽類參數
    private lateinit var btManager: BluetoothManager
    private var btAdapter: BluetoothAdapter? = null
    private lateinit var btDevice: BluetoothDevice
    //藍芽Gatt類參數
    private var btGatt: BluetoothGatt? = null
    private var btGattService : BluetoothGattService? = null
    private var btGattCharacteristicRx : BluetoothGattCharacteristic? = null
    private var btGattCharacteristicTx : BluetoothGattCharacteristic? = null
    //private var btGattDescriptor : BluetoothGattDescriptor? = null
    private var scanning = false
    private lateinit var myList : List<Pair<String,String>>
    private lateinit var adapter : ArrayAdapter<Pair<String,String>>

    private lateinit var loopCheckBox : CheckBox
    private lateinit var loopSeekBar: SeekBar
    private lateinit var btScanBtn : Button
    private lateinit var btShowDeviceBtn : Button
    private lateinit var btSendDataBtn : Button
    private lateinit var btnCheckPermission : Button
    private lateinit var btDisconnectBtn : Button
    private lateinit var tvlab: TextView
    private lateinit var tvMsg : TextView
    private lateinit var tvList : TextView

    private val mythread = MyThread()
    //伴生物件
    companion object BtCompanionObject{
        var isRunning = false
        var isDisconnect = false
        var intervalTime : Int = 3
        //var devList : MutableList<BluetoothDevice> = mutableListOf()
        //var devName : MutableList<String> = mutableListOf()
        var devMap : MutableMap<String,String> = mutableMapOf()
        //val invoice : String = "1234567890" + 0x0D.toChar() + 0x0A.toChar()  // == "1234567890" + "\r\n"
        const val space : String= "" + 0x0D.toChar() + 0x0A.toChar()
        //val requestWP103StateCode = byteArrayOf(0x0D,0x0A)
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        //supportActionBar?.title = "WP-Series BLE Transfer tools"
        val toolbarx: androidx.appcompat.widget.Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbarx)
        //產生視圖物件
        creatWidgetView()

        //val mythread = myThread()

        //註冊藍芽開啟回呼
        enableBTlaunch =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { resuilt ->
                if (resuilt.resultCode == RESULT_OK) {
                    Log.i(MYTAG, "Bluetooth Enabled (Adaptor)")
                    Toast.makeText(this, "Bluetooth Enabled", Toast.LENGTH_SHORT).show()
                } else {
                    Log.e(MYTAG, "Bluetooth Adaptor Permission Denial")
                    Toast.makeText(this, "Bluetooth Disabled && END APP", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }

        btManager = getSystemService(BluetoothManager::class.java)
        //請求動態權限
        dynamicGetPermission()
        //取得藍芽配適器
        btAdapter = btManager.adapter
        //檢查藍芽是否開啟
        if (btAdapter == null) {
            Log.e(MYTAG, "The Device doesn't support Bluetooth")   //裝置不支持藍芽
            finish()
        } else if (!btAdapter!!.isEnabled) {
            enableBTlaunch.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))   //Intent : 開啟藍芽
        }
        //按鍵 : 藍芽掃描
        btScanBtn.setOnClickListener { btAdapter?.let { ad -> startScanBle(ad) } }
        //按鍵 : 藍芽裝置顯示
        btShowDeviceBtn.setOnClickListener { btnShowDevice() }
        //按鍵 : 藍芽發送資料
        btSendDataBtn.setOnClickListener {
            btGatt?.let { gt ->
                if (loopCheckBox.isChecked)
                    btnSendData(gt,btGattCharacteristicRx!!)
                else
                    //btnSendData(gt,btGattCharacteristicRx!!,btGattCharacteristicTx!!) }
                    btnSendData(gt, btGattCharacteristicRx!!)
            }
        }
        //按鍵 : 藍芽相關權限顯示
        btnCheckPermission.setOnClickListener { btnCheckPermission() }
        //按鍵 : 藍芽取消連線
        btDisconnectBtn.setOnClickListener {
            if (btGatt != null) {
                btGatt!!.disconnect()
                isDisconnect = true
                btSendDataBtn.text = getText(R.string.send_btn)
                Log.d(MYTAG, "BluetoothGATT Disconnected")
            }
        }
        loopCheckBox.setOnCheckedChangeListener { compoundButton, state ->
            val viewId = compoundButton.id
            when (viewId) {
                R.id.checkboxLoop -> loopSeekBar.isEnabled = state
            }
        }
        loopSeekBar.setOnSeekBarChangeListener(object :SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(p0: SeekBar?, p1: Int, p2: Boolean) {}
            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {
                val sb = p0?.id
                when(sb){
                    R.id.seekbarLoop ->{
                        tvMsg.text = p0.progress.toString()
                    }
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        btGatt?.let {
            if (ActivityCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            it.disconnect()
            it.close()
            btGatt = null
        }
        mythread.interrupt()
    }
    //請求動態權限
    private fun dynamicGetPermission(){
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
            requestPermissions(arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),ANDROID_12_PERMISSION_CODE)
        }else { // >= Build.VERSION_CODES.M
            requestPermissions(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),ANDROID_6_PERMISSION_CODE)
        }
    }
    //產生視圖物件
    private fun creatWidgetView() {
        //supportActionBar?.setTitle("WP-Series BLE transfer tools")
        loopCheckBox = findViewById(R.id.checkboxLoop)
        loopSeekBar = findViewById(R.id.seekbarLoop)
        loopCheckBox.isChecked = false
        loopSeekBar.isEnabled = loopCheckBox.isChecked

        btScanBtn = findViewById(R.id.btnBleScan)
        btShowDeviceBtn = findViewById(R.id.btnshowdevice)
        btSendDataBtn = findViewById(R.id.btnSendData)
        btnCheckPermission = findViewById(R.id.btncheckPermisssion)
        btDisconnectBtn = findViewById(R.id.btnDisConnect)
        tvlab = findViewById(R.id.textlable)
        tvMsg = findViewById(R.id.textmsg)
        tvList = findViewById(R.id.textList)
    }
    //檢查藍芽相關權限授權與否
    private fun btnCheckPermission(){
        var msg: String = if(ActivityCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) "Connect : Grant\n" else "Connect : denial\n"
        msg += if (ActivityCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) " Scan : Grant\n" else " Scan : denial\n"
        msg += if(ActivityCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) " Fine : Grant\n" else " Fine : denial\n"
        msg += if(ActivityCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) " Coarse : Grant" else " Coarse : denial"

        AlertDialog.Builder(this)
            .setTitle("Permission Now")
            .setMessage(msg)
            .show()

        //tvSele.text = msg
    }
    //使用者藍芽權限回呼
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when(requestCode){
            BLUETOOTH_CONNECT_PERMISSION_CODE->{
               if(grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                   Toast.makeText(this, "BT Connect Grant", Toast.LENGTH_LONG).show()
                   Log.i(MYTAG, "Bluetooth Connect Grant")
               }else {
                   Toast.makeText(this, "BT Connect Denial", Toast.LENGTH_LONG).show()
                   Log.i(MYTAG, "Bluetooth Connect Denial")
               }
            }
            BLUETOOTH_SCAN_PERMISSION_CODE-> {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "BT Scan Grant", Toast.LENGTH_LONG).show()
                    Log.i(MYTAG, "Bluetooth Scan Grant")
                }else {
                    Toast.makeText(this, "BT Scan Denial", Toast.LENGTH_LONG).show()
                    Log.i(MYTAG, "Bluetooth Scan Denial")
                }
            }
            ANDROID_12_PERMISSION_CODE->{
                if(permissions.isEmpty() &&
                    permissions[0] == Manifest.permission.BLUETOOTH_CONNECT &&
                    permissions[1] == Manifest.permission.BLUETOOTH_SCAN &&
                    permissions[2] == Manifest.permission.ACCESS_FINE_LOCATION &&
                    permissions[3] == Manifest.permission.ACCESS_COARSE_LOCATION
                    ){
                    val context = PackageManager.PERMISSION_GRANTED
                    if(grantResults[0] == context &&
                        grantResults[1] == context &&
                        grantResults[2] == context &&
                        grantResults[3] == context
                        )
                        Log.i(MYTAG,"GRANT: Bluetooth Connect,Scan,Fine,Coarse ")
                    else
                        Log.i(MYTAG,"Denial: Bluetooth Connect,Scan,Fine,Coarse")
                }


            }
            ANDROID_6_PERMISSION_CODE->{
                if(grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED)
                    Log.i(MYTAG,"Fine & Coarse Grant")
                else
                    Log.i(MYTAG,"fine & Coarse Denial")
            }
        }
    }

    //藍芽掃描開始/停止
    @SuppressLint("MissingPermission")
    private fun startScanBle(bleAdapter: BluetoothAdapter?){

        //val filters = ScanFilter.Builder().setDeviceName("WP551").build()
        //val scanFilter = mutableListOf(filters)
        //val scanSettings = ScanSettings.Builder().build()

        val bleScanner = bleAdapter!!.bluetoothLeScanner
        if (!scanning) {

            //devList.clear()
            //devName.clear()
            devMap.clear()

            Handler(Looper.getMainLooper()).postDelayed({
                scanning = false
                bleScanner.stopScan(leScanCallback)
                btShowDeviceBtn.isEnabled = true
            }, scanDuration)
            scanning = true

            bleScanner.startScan(leScanCallback)
            //bleScanner.startScan(scanFilter,scanSettings,leScanCallback)
            if(btGatt == null) btSendDataBtn.isEnabled = false
            btShowDeviceBtn.isEnabled = false
        } else {
            scanning = false
            bleScanner.stopScan(leScanCallback)
            btShowDeviceBtn.isEnabled = true
        }
    }

    //藍芽掃描結果回呼
    @SuppressLint("MissingPermission")
    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.device?.let { dev ->
                dev.name?.let{ name ->
                    if(name.contains("WP",ignoreCase = true))
                        devMap[name] = dev.address
                }
            }
        }
    }

    //按鍵顯示藍芽裝置清單
    @SuppressLint("MissingPermission")
    private fun btnShowDevice(){
        if(!scanning){
            /*
            // The one method of showing the AlertDialog
            val myList : List<Pair<String,String>> = devMap.toList()
            val adapter = ArrayAdapter(this,android.R.layout.simple_list_item_1,myList.map { "${it.first}     ${it.second}" })
            val builder = AlertDialog.Builder(this)
                .setTitle("Select a Bluetooth device")
                .setAdapter(adapter) { _, which ->
                    btGatt?.let {
                        it.disconnect()
                        it.close()
                        btGatt = null
                    }
                    btDevice = btAdapter!!.getRemoteDevice(myList[which].second)
                    btGatt = btDevice.connectGatt(this,false,gattCallback(),BluetoothDevice.TRANSPORT_LE)
                    btSendDataBtn.isEnabled = false
                    tvMsg.text = ""
                }.create().show()*/

            /*
            // The two method of showing the AlertDialog
            val itemArray : Array<String> = devMap.keys.toTypedArray()

            val builder = AlertDialog.Builder(this)
                .setTitle("Select a Bluetooth device")
                .setItems(itemArray) { _, which ->
                    btGatt?.let {
                        it.disconnect()
                        it.close()
                        btGatt = null
                    }
                    btDevice = btAdapter!!.getRemoteDevice(devMap[itemArray[which]])
                    btGatt = btDevice.connectGatt(this,false,gattCallback(),BluetoothDevice.TRANSPORT_LE)
                    btSendDataBtn.isEnabled = false
                    tvMsg.text = ""
                }.create().show()*/
            myList = devMap.toList()
            adapter = object : ArrayAdapter<Pair<String,String>>(this,R.layout.dialog_two_columns,myList){
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.dialog_two_columns,parent,false)
                    val item = getItem(position)
                    view.findViewById<TextView>(R.id.column1).text = item?.first
                    view.findViewById<TextView>(R.id.column2).text = item?.second
                    //return super.getView(position, convertView, parent)
                    return view
                }
            }

            AlertDialog.Builder(this)
                .setTitle("Select a Bluetooth device")
                .setAdapter(adapter){ _, which->
                    btGatt?.let {
                        it.disconnect()
                        it.close()
                        btGatt = null
                    }

                    btDevice = btAdapter!!.getRemoteDevice(myList[which].second)
                    btGatt = btDevice.connectGatt(this,false,GattCallback(),BluetoothDevice.TRANSPORT_LE)
                    tvMsg.text = getText(R.string.emptyString)
                }.create().show()

        }else{
            Toast.makeText(this,"Please wait BtScan End",Toast.LENGTH_SHORT).show()
        }
    }
    //覆寫藍芽Gatt回調
    private inner class GattCallback:BluetoothGattCallback(){
    //private val gettCallback = object : BluetoothGattCallback(){
        //private val SDP_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
        private val rxUUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
        private val txUUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

        // 连接状态变化的回调
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            if(BluetoothGatt.GATT_SUCCESS == status){
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d("BluetoothGattCallback", "Connected to GATT server")
                        gatt!!.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        //Toast.makeText(this@MainActivity,"Disconnected from GATT server",Toast.LENGTH_SHORT).show()
                        Log.d("BluetoothGattCallback", "Disconnected from GATT server")
                    }
                }
            }else{
                btGatt?.let { bg ->
                    bg.disconnect()
                    bg.close()
                    btGatt = null
                }
            }
        }

        // 发现服务的回调
        //@RequiresApi(Build.VERSION_CODES.TIRAMISU)
        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if(status == BluetoothGatt.GATT_SUCCESS){
                btGattService = null
                btGattCharacteristicRx = null
                btGattCharacteristicTx = null
                val gattServiceList = gatt!!.services   //取得 gattService 集合

                gattServiceList.forEach { service ->
                    //if (service.uuid.toString().equals(SDP_UUID,ignoreCase = true)){  //檢查 gattService UUID
                        val gattCharacteristicList = service.characteristics    //取得 gattcharacteristics 集合

                        gattCharacteristicList.forEach { gattCharacteristic ->
                            when {
                                gattCharacteristic.uuid.toString().equals(rxUUID,ignoreCase = true) ->{
                                    btGattCharacteristicRx = gattCharacteristic
                                    if (btGattService == null) btGattService = gattCharacteristic.service
                                    Log.d(MYTAG,"onServicesDiscovered : Characteristic Get(Rx)")
                                }
                                gattCharacteristic.uuid.toString().equals(txUUID,ignoreCase = true) ->{
                                    btGattCharacteristicTx = gattCharacteristic
                                    if (btGattService == null) btGattService = gattCharacteristic.service
                                    Log.d(MYTAG,"onServicesDiscovered : Characteristic Get(Tx)")
                                }
                            }
                        }
                    //}
                }

                Thread{
                    Handler(Looper.getMainLooper()).post {
                        btGattCharacteristicRx?.let {
                            tvMsg.text = btDevice.name.toString()
                            btDisconnectBtn.isEnabled = true
                            btSendDataBtn.isEnabled = true
                        }
                    }
                }.start()
            }else {
                Log.e("onServicesDiscovered", "GATT FAILURE")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if(status == BluetoothGatt.GATT_SUCCESS){
                Log.d("onCharacteristicWrite","GATT WRITE SUCCESS")
                //Toast.makeText(this@MainActivity,"GATT write success",Toast.LENGTH_SHORT).show()
            }else{
                Log.d("onCharacteristicWrite","GATT WRITED FAILED")
                //Toast.makeText(this@MainActivity,"GATT write Failed",Toast.LENGTH_SHORT).show()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            Runnable {
                Handler(Looper.getMainLooper()).post {
                    tvMsg.text = String(value)
                }
            }.run()
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, value, status)
            if(status == BluetoothGatt.GATT_SUCCESS){
                val tval = String(value)
                tvMsg.text = tval
            }else{
                val tval = "onCharacteristicRead_fial"
                tvMsg.text = tval
            }
        }
    }

    private fun btnSendData(gatt: BluetoothGatt?,gattRx: BluetoothGattCharacteristic?){
        val dataByte = space.toByteArray()

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        if (btGattCharacteristicRx == null){
            return
        }

        /** //Android 13以上檢查 Connect Permission 並要求授權
        val listDev = btManager.getConnectedDevices(BluetoothProfile.GATT)
        if (listDev.isEmpty()){
            Toast.makeText(this,"Remote Device Not Ready",Toast.LENGTH_SHORT).show()
            return
        }*/

        //停止連續列印
        if(loopCheckBox.isChecked && isRunning){
            isRunning = false
            btSendDataBtn.text = getText(R.string.send_btn)
            myHandler.removeCallbacks(runnable)
            return

        //開始連續列印
        }else if(loopCheckBox.isChecked && !isRunning){
            isRunning = true

            //使用Runnable 的方式重複
            runnable = Runnable {
                if(isRunning){
                    btGattCharacteristicRx!!.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    btGattCharacteristicRx!!.setValue(dataByte)
                    gatt!!.writeCharacteristic(gattRx)
                    myHandler.postDelayed(runnable, (intervalTime*1000).toLong())
                }
            }
            
            if(isDisconnect){
                btGatt?.let{
                    if(it.connect()){
                        myHandler.post(runnable)
                        isDisconnect = false
                    }
                }
            }else{
                myHandler.post(runnable)
            }

            btSendDataBtn.text = getText(R.string.stop_btn)
            /** //使用Thread 的方式重複
                mythread = Thread{
                while(!Thread.currentThread().isInterrupted){
                gattWriteHandler.post{
                gR.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                gR.setValue(dataByte)
                gatt!!.writeCharacteristic(gattRx)
                }
                try{
                Thread.sleep(interval)
                }catch (e:InterruptedException){
                break
                }
                }
                }
                mythread.start()*/
                //mythread.start()
        //開始單次列印
        }else{
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                btGattCharacteristicRx?.let {
                    gatt!!.writeCharacteristic(it,dataByte,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                }
            }else{
                // https://docs.nordicsemi.com/bundle/sdk_nrf5_v17.1.0/page/ble_sdk_app_nus_eval.html
                btGattCharacteristicRx!!.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                btGattCharacteristicRx!!.setValue(dataByte)
                gatt!!.writeCharacteristic(gattRx)
                Log.d("btnSendData", "Write Data to Gatt")
            }
        }
            /**
            gattTx?.let {gT ->
                gatt!!.setCharacteristicNotification(gattTx,true)
                val descriptor : BluetoothGattDescriptor = gattTx.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }**/
    }

    inner class MyThread : Thread(){
        @Volatile
        private var isrunning = true

        @SuppressLint("MissingPermission")
        override fun run() {
            val dataByte = space
            while (isrunning){
                    btGattCharacteristicRx?.writeType ?: BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    btGattCharacteristicRx!!.setValue(dataByte)
                    btGatt!!.writeCharacteristic(btGattCharacteristicRx)
                try{
                    sleep((intervalTime*1000).toLong())
                }catch (e:InterruptedException){
                    break
                }
            }
        }

        /*fun stopThread(){
            isrunning = false
        }*/
    }

    /*@SuppressLint("MissingPermission")
    val builder = AlertDialog.Builder(this@MainActivity)
        .setTitle("Choose an Device")
        .setItems(itemArray){ _,which->
            btGatt?.let { it ->
                it.disconnect()
                it.close()
            }
            btDevice = btAdapter!!.getRemoteDevice(devMap[itemArray[which]])
            btGatt = btDevice.connectGatt(this,false,gattCallback(),BluetoothDevice.TRANSPORT_LE)
            btSendDataBtn.isEnabled = false
            tvSele.text = ""
        }*/
}
