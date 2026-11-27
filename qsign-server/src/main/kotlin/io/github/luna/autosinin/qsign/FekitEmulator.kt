package io.github.luna.autosinin.qsign

import com.github.unidbg.AndroidEmulator
import com.github.unidbg.Emulator
import com.github.unidbg.arm.context.Arm64RegisterContext
import com.github.unidbg.file.linux.AndroidFileIO
import com.github.unidbg.linux.ARM64SyscallHandler
import com.github.unidbg.linux.android.AndroidARM64Emulator
import com.github.unidbg.linux.android.AndroidResolver
import com.github.unidbg.linux.android.dvm.AbstractJni
import com.github.unidbg.linux.android.dvm.BaseVM
import com.github.unidbg.linux.android.dvm.DvmClass
import com.github.unidbg.linux.android.dvm.DvmObject
import com.github.unidbg.linux.android.dvm.StringObject
import com.github.unidbg.linux.android.dvm.VM
import com.github.unidbg.linux.android.dvm.VaList
import com.github.unidbg.linux.android.dvm.array.ArrayObject
import com.github.unidbg.linux.android.dvm.array.ByteArray as DvmByteArray
import com.github.unidbg.memory.Memory
import com.github.unidbg.memory.SvcMemory
import com.github.unidbg.unix.UnixSyscallHandler
import com.sun.jna.Pointer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 基于 Unidbg Android64 模拟执行 QQ Android libfekit.so (9.3.85)。
 */
class FekitEmulator(
    private val fekitSo: File,
    private val qsecSo: File? = null,
    private val rootDir: File = File("data/qsign/rootfs"),
    private val qqVersion: String = "9.3.85",
    private val qua: String = "V1_AND_SQ_9.3.85_16920_YYB_D",
) : AbstractJni() {

    data class SignOutput(
        var sign: ByteArray = ByteArray(0),
        var token: ByteArray = ByteArray(0),
        var extra: ByteArray = ByteArray(0),
    )

    private val emulator: AndroidEmulator
    private val memory: Memory
    private val vm: VM

    private val dtnClass: DvmClass
    private val qqSecuritySignClass: DvmClass
    private val qsecClass: DvmClass
    private val dandelionClass: DvmClass
    private val contextObj: DvmObject<*>

    private var currentUin: String = "0"
    private var currentGuid: String = "be9741c6cffd0a340d99c75610001751"
    private var currentQ36: String = "be9741c6cffd0a340d99c75610001751a904"
    private val mmkvStore = mutableMapOf<String, String>()
    private val mmkvBytesStore = mutableMapOf<String, ByteArray>()

    init {
        rootDir.mkdirs()
        File(rootDir, "data/data/com.tencent.mobileqq/files/5463306EE50FE3AA").mkdirs()

        emulator = object : AndroidARM64Emulator("com.tencent.mobileqq", rootDir, emptyList()) {
            override fun createSyscallHandler(svcMemory: SvcMemory): UnixSyscallHandler<AndroidFileIO> {
                return object : ARM64SyscallHandler(svcMemory) {
                    override fun clock_gettime(emulator: Emulator<*>): Int {
                        val ctx = emulator.getContext<Arm64RegisterContext>()
                        val clkId = ctx.getIntArg(0)
                        if (clkId == 2 || clkId == 3) {
                            val tp: Pointer? = ctx.getPointerArg(1)
                            val nano = System.nanoTime()
                            if (tp != null) {
                                tp.setLong(0, nano / 1_000_000_000L)
                                tp.setLong(8, nano % 1_000_000_000L)
                            }
                            return 0
                        }
                        return super.clock_gettime(emulator)
                    }
                }
            }
        }
        memory = emulator.memory
        memory.setLibraryResolver(AndroidResolver(23))

        vm = emulator.createDalvikVM(null as File?)
        vm.setJni(this)
        vm.setVerbose(false)

        if (qsecSo != null && qsecSo.exists()) {
            val qsecMod = vm.loadLibrary(qsecSo, true)
            qsecMod.callJNI_OnLoad(emulator)
        }
        val fekitMod = vm.loadLibrary(fekitSo, true)
        fekitMod.callJNI_OnLoad(emulator)

        dtnClass = vm.resolveClass("com/tencent/mobileqq/dt/Dtn")
        qqSecuritySignClass = vm.resolveClass("com/tencent/mobileqq/sign/QQSecuritySign")
        qsecClass = vm.resolveClass("com/tencent/mobileqq/qsec/qsecurity/QSec")
        dandelionClass = vm.resolveClass("com/tencent/mobileqq/qsec/qsecdandelionsdk/Dandelion")
        contextObj = vm.resolveClass("android/content/Context").newObject(null)

        initNative()
    }

    private fun initNative() {
        val dtnObj = dtnClass.newObject(null)
        val signObj = qqSecuritySignClass.newObject(null)
        signObj.callJniMethod(emulator, "initSafeMode(Z)V", false)
        dtnObj.callJniMethod(
            emulator,
            "initGlobalConfig(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
            StringObject(vm, currentGuid),
            StringObject(vm, ""),
            StringObject(vm, currentQ36),
            StringObject(vm, "0"),
            StringObject(vm, ""),
            StringObject(vm, qqVersion),
            StringObject(vm, qua),
        )
        dtnObj.callJniMethod(
            emulator,
            "initNativeContext(Landroid/content/Context;Ljava/lang/String;)V",
            contextObj,
            StringObject(vm, "/data/data/com.tencent.mobileqq/files/5463306EE50FE3AA"),
        )
        dtnObj.callJniMethod(emulator, "initUin(Ljava/lang/String;)V", StringObject(vm, currentUin))
        dtnObj.callJniMethod(emulator, "initfinally()V")
    }

    @Synchronized
    fun updateUin(uin: String, guid: String? = null) {
        if (!guid.isNullOrBlank()) currentGuid = guid
        if (uin != currentUin) {
            currentUin = uin
            dtnClass.newObject(null).callJniMethod(emulator, "initUin(Ljava/lang/String;)V", StringObject(vm, uin))
        }
    }

    @Synchronized
    fun sign(
        uin: String,
        cmd: String,
        buffer: ByteArray,
        seq: Int,
        quaOverride: String? = null,
        guidOverride: String? = null,
    ): SignOutput {
        updateUin(uin, guidOverride)
        val seqBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(seq).array()
        val qsecObj = qsecClass.newObject(null)
        val signObj = qqSecuritySignClass.newObject(null)
        val resObj = signObj.callJniMethodObject<DvmObject<*>>(
            emulator,
            "getSign(Lcom/tencent/mobileqq/qsec/qsecurity/QSec;Ljava/lang/String;Ljava/lang/String;[B[BLjava/lang/String;)Lcom/tencent/mobileqq/sign/QQSecuritySign\$SignResult;",
            qsecObj,
            StringObject(vm, quaOverride ?: qua),
            StringObject(vm, cmd),
            DvmByteArray(vm, buffer),
            DvmByteArray(vm, seqBytes),
            StringObject(vm, uin),
        )
        val raw = resObj?.value as? SignOutput
        return raw ?: SignOutput(ByteArray(0), ByteArray(0), ByteArray(0))
    }

    @Synchronized
    fun energy(uin: String, salt: String, data: ByteArray, guidOverride: String? = null): ByteArray {
        updateUin(uin, guidOverride)
        val obj = dandelionClass.newObject(null)
        val res = obj.callJniMethodObject<DvmByteArray>(
            emulator,
            "energy(Ljava/lang/Object;Ljava/lang/Object;)[B",
            StringObject(vm, salt),
            DvmByteArray(vm, data),
        )
        return res?.value ?: ByteArray(0)
    }

    @Synchronized
    fun getDebugXwid(uin: String, data: String, guidOverride: String? = null): ByteArray {
        updateUin(uin, guidOverride)
        val qsecObj = qsecClass.newObject(null)
        val res = qsecObj.callJniMethodObject<DvmByteArray>(
            emulator,
            "getXwDebugID(Ljava/lang/String;)[B",
            StringObject(vm, data),
        )
        return res?.value ?: ByteArray(0)
    }

    override fun allocObject(vm: BaseVM, dvmClass: DvmClass, signature: String): DvmObject<*>? {
        return when (signature) {
            "com/tencent/mobileqq/sign/QQSecuritySign\$SignResult->allocObject" ->
                dvmClass.newObject(SignOutput(ByteArray(0), ByteArray(0), ByteArray(0)))
            else -> super.allocObject(vm, dvmClass, signature)
        }
    }

    override fun newObjectV(vm: BaseVM, dvmClass: DvmClass, signature: String, vaList: VaList): DvmObject<*> {
        return when (signature) {
            "com/tencent/mobileqq/sign/QQSecuritySign\$SignResult-><init>()V" ->
                dvmClass.newObject(SignOutput(ByteArray(0), ByteArray(0), ByteArray(0)))
            else -> super.newObjectV(vm, dvmClass, signature, vaList)
        }
    }

    override fun setObjectField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, value: DvmObject<*>?) {
        val out = dvmObject.value as? SignOutput
        val bytes = (value as? DvmByteArray)?.value ?: ByteArray(0)
        if (out != null) {
            when (signature) {
                "com/tencent/mobileqq/sign/QQSecuritySign\$SignResult->sign:[B" -> {
                    out.sign = bytes
                    return
                }
                "com/tencent/mobileqq/sign/QQSecuritySign\$SignResult->token:[B" -> {
                    out.token = bytes
                    return
                }
                "com/tencent/mobileqq/sign/QQSecuritySign\$SignResult->extra:[B" -> {
                    out.extra = bytes
                    return
                }
            }
        }
        super.setObjectField(vm, dvmObject, signature, value)
    }

    override fun getStaticObjectField(vm: BaseVM, dvmClass: DvmClass, signature: String): DvmObject<*>? {
        println("[JNI getStaticObjectField] $signature")
        return super.getStaticObjectField(vm, dvmClass, signature)
    }

    override fun callStaticObjectMethodV(
        vm: BaseVM,
        dvmClass: DvmClass,
        signature: String,
        vaList: VaList,
    ): DvmObject<*>? {
        return when (signature) {
            "com/tencent/mobileqq/fe/FEKit->getInstance()Lcom/tencent/mobileqq/fe/FEKit;" ->
                dvmClass.newObject(null)
            "com/tencent/mobileqq/dt/model/FEBound->transform(I[B)[B" -> {
                val type = vaList.getIntArg(0)
                val arrObj = vaList.getObjectArg<DvmByteArray>(1)
                val input = arrObj?.value ?: ByteArray(0)
                DvmByteArray(vm, feBoundTransform(type, input))
            }
            "com/tencent/mobileqq/dt/app/Dtc->getAppVersionName(Ljava/lang/String;)Ljava/lang/String;" -> {
                StringObject(vm, "9.3.85")
            }
            "com/tencent/mobileqq/dt/app/Dtc->getAndroidID(Ljava/lang/String;)Ljava/lang/String;" -> {
                StringObject(vm, REAL_ANDROID_ID)
            }
            "com/tencent/mobileqq/dt/app/Dtc->getPropSafe(Ljava/lang/String;)Ljava/lang/String;" -> {
                val prop = vaList.getObjectArg<StringObject>(0)?.value.orEmpty()
                StringObject(vm, SYSTEM_PROPS[prop] ?: "")
            }
            "com/tencent/mobileqq/dt/app/Dtc->getDensity(Ljava/lang/String;)Ljava/lang/String;" -> {
                StringObject(vm, REAL_DENSITY)
            }
            "com/tencent/mobileqq/dt/app/Dtc->getScreenSize(Ljava/lang/String;)Ljava/lang/String;" -> {
                StringObject(vm, REAL_SCREEN_SIZE)
            }
            "com/tencent/mobileqq/dt/app/Dtc->systemGetsafe(Ljava/lang/String;)Ljava/lang/String;" -> {
                val prop = vaList.getObjectArg<StringObject>(0)?.value.orEmpty()
                StringObject(vm, SYSTEM_PROPS[prop] ?: "")
            }
            "com/tencent/mobileqq/dt/app/Dtc->getUUID(Ljava/lang/String;)Ljava/lang/String;" -> {
                StringObject(vm, formatGuidAsUuid(currentGuid))
            }
            "com/tencent/mobileqq/dt/app/Dtc->mmKVValue(Ljava/lang/String;)Ljava/lang/String;" -> {
                val key = vaList.getObjectArg<StringObject>(0)?.value
                StringObject(vm, mmkvStore[key] ?: "")
            }
            "com/tencent/mobileqq/dt/app/Dtc->mmQsecKVValue(Ljava/lang/String;)Ljava/lang/String;" -> {
                val key = vaList.getObjectArg<StringObject>(0)?.value
                StringObject(vm, mmkvStore[key] ?: "")
            }
            "com/tencent/mobileqq/dt/app/Dtc->mmQsecKVValueBytes(Ljava/lang/String;)[B" -> {
                val key = vaList.getObjectArg<StringObject>(0)?.value
                DvmByteArray(vm, mmkvBytesStore[key] ?: ByteArray(0))
            }
            "java/lang/Thread->currentThread()Ljava/lang/Thread;" -> {
                vm.resolveClass("java/lang/Thread").newObject(Thread.currentThread())
            }
            else -> {
                println("[JNI callStaticObjectMethodV] $signature")
                super.callStaticObjectMethodV(vm, dvmClass, signature, vaList)
            }
        }
    }

    override fun callObjectMethodV(
        vm: BaseVM,
        dvmObject: DvmObject<*>,
        signature: String,
        vaList: VaList,
    ): DvmObject<*>? {
        return when (signature) {
            "java/lang/Thread->getStackTrace()[Ljava/lang/StackTraceElement;" -> {
                ArrayObject()
            }
            else -> {
                println("[JNI callObjectMethodV] $signature")
                super.callObjectMethodV(vm, dvmObject, signature, vaList)
            }
        }
    }

    override fun getObjectField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): DvmObject<*>? {
        println("[JNI getObjectField] $signature")
        return super.getObjectField(vm, dvmObject, signature)
    }

    override fun callIntMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Int {
        return when (signature) {
            "java/lang/String->hashCode()I" -> (dvmObject.value as? String)?.hashCode() ?: 0
            else -> {
                println("[JNI callIntMethodV] $signature")
                super.callIntMethodV(vm, dvmObject, signature, vaList)
            }
        }
    }

    override fun callBooleanMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Boolean {
        println("[JNI callBooleanMethodV] $signature")
        return super.callBooleanMethodV(vm, dvmObject, signature, vaList)
    }

    override fun callStaticVoidMethodV(vm: BaseVM, dvmClass: DvmClass, signature: String, vaList: VaList) {
        when (signature) {
            "com/tencent/mobileqq/dt/app/Dtc->mmKVQsecSaveValue(Ljava/lang/String;Ljava/lang/String;)V",
            "com/tencent/mobileqq/dt/app/Dtc->mmKVSaveValue(Ljava/lang/String;Ljava/lang/String;)V" -> {
                val key = vaList.getObjectArg<StringObject>(0)?.value
                val value = vaList.getObjectArg<StringObject>(1)?.value
                if (key != null && value != null) mmkvStore[key] = value
            }
            "com/tencent/mobileqq/dt/app/Dtc->mmKVQsecSaveValueBytes(Ljava/lang/String;[B)V" -> {
                val key = vaList.getObjectArg<StringObject>(0)?.value
                val value = vaList.getObjectArg<DvmByteArray>(1)?.value
                if (key != null && value != null) mmkvBytesStore[key] = value
            }
            else -> {
                println("[JNI callStaticVoidMethodV] $signature")
                super.callStaticVoidMethodV(vm, dvmClass, signature, vaList)
            }
        }
    }

    fun close() {
        runCatching { emulator.close() }
    }

    companion object {
        private const val REAL_ANDROID_ID = "82925c0e28a82a89"
        private const val REAL_DENSITY = "2.625"
        private const val REAL_SCREEN_SIZE = "1080x2400"

        private val SYSTEM_PROPS = mapOf(
            "ro.product.manufacturer" to "Xiaomi",
            "ro.product.brand" to "Redmi",
            "ro.product.model" to "22041211AC",
            "ro.product.name" to "rubens",
            "ro.product.device" to "rubens",
            "ro.product.board" to "rubens",
            "ro.board.platform" to "mt6895",
            "ro.hardware" to "mt6895",
            "ro.build.version.release" to "14",
            "ro.build.version.sdk" to "34",
            "ro.build.version.incremental" to "OS2.0.5.0.ULNCNXM",
            "ro.build.fingerprint" to "Redmi/rubens/rubens:14/UP1A.231005.007/OS2.0.5.0.ULNCNXM:user/release-keys",
            "ro.build.display.id" to "UP1A.231005.007",
            "ro.serialno" to "ONRONJUWDMIJIN5L",
        )

        private fun formatGuidAsUuid(guid: String): String {
            val clean = guid.trim().lowercase()
            return if (clean.length == 32) {
                "${clean.substring(0, 8)}-${clean.substring(8, 12)}-${clean.substring(12, 16)}-${clean.substring(16, 20)}-${clean.substring(20, 32)}"
            } else {
                "be9741c6-cffd-0a34-0d99-c75610001751"
            }
        }

        private val CONFIG_ENCODE = arrayOf(
            byteArrayOf(14, 1, 13, 10, 2, 7, 6, 5, 0, 12, 4, 8, 11, 9, 15, 3),
            byteArrayOf(10, 0, 12, 6, 9, 4, 8, 15, 3, 7, 13, 5, 14, 11, 1, 2),
            byteArrayOf(6, 2, 0, 13, 4, 10, 14, 11, 15, 1, 12, 3, 9, 5, 8, 7),
            byteArrayOf(2, 10, 4, 8, 15, 14, 0, 3, 13, 12, 11, 6, 7, 5, 1, 9),
            byteArrayOf(14, 3, 11, 12, 0, 6, 9, 13, 8, 4, 7, 1, 2, 15, 10, 5),
            byteArrayOf(10, 11, 5, 4, 8, 12, 13, 0, 15, 7, 1, 9, 2, 6, 3, 14),
            byteArrayOf(7, 1, 9, 14, 3, 5, 11, 13, 6, 2, 12, 10, 0, 15, 4, 8),
            byteArrayOf(3, 10, 0, 5, 6, 7, 11, 2, 4, 14, 15, 1, 12, 8, 13, 9),
            byteArrayOf(15, 2, 14, 13, 1, 0, 4, 7, 5, 3, 9, 10, 8, 6, 11, 12),
            byteArrayOf(11, 10, 8, 4, 6, 0, 12, 5, 7, 9, 13, 2, 1, 3, 14, 15),
            byteArrayOf(7, 3, 1, 12, 14, 8, 10, 5, 6, 13, 11, 15, 4, 9, 2, 0),
            byteArrayOf(3, 11, 13, 0, 6, 5, 7, 8, 9, 1, 15, 14, 12, 10, 2, 4),
            byteArrayOf(15, 3, 7, 11, 14, 5, 12, 2, 13, 4, 10, 0, 1, 8, 9, 6),
            byteArrayOf(11, 12, 2, 3, 5, 14, 1, 15, 7, 10, 4, 8, 9, 6, 13, 0),
            byteArrayOf(7, 4, 12, 10, 13, 11, 8, 2, 14, 15, 0, 6, 3, 1, 5, 9),
            byteArrayOf(3, 12, 7, 4, 2, 8, 13, 9, 6, 11, 10, 14, 0, 15, 1, 5),
            byteArrayOf(0, 3, 10, 11, 1, 12, 9, 4, 7, 6, 15, 5, 13, 2, 14, 8),
            byteArrayOf(12, 11, 5, 3, 13, 14, 15, 6, 4, 8, 9, 10, 2, 7, 1, 0),
            byteArrayOf(8, 3, 15, 10, 11, 4, 9, 13, 1, 7, 6, 14, 5, 0, 12, 2),
            byteArrayOf(4, 12, 10, 6, 13, 9, 5, 7, 2, 8, 11, 0, 3, 14, 15, 1),
            byteArrayOf(0, 4, 1, 10, 11, 5, 6, 15, 13, 9, 3, 2, 8, 14, 12, 7),
            byteArrayOf(12, 0, 14, 15, 9, 1, 3, 13, 2, 4, 7, 6, 10, 8, 11, 5),
            byteArrayOf(8, 5, 9, 2, 10, 14, 11, 13, 7, 0, 1, 12, 6, 3, 15, 4),
            byteArrayOf(4, 13, 3, 9, 5, 1, 11, 2, 6, 8, 10, 0, 15, 14, 7, 12),
            byteArrayOf(0, 5, 14, 8, 10, 3, 2, 7, 13, 1, 11, 12, 9, 15, 6, 4),
            byteArrayOf(13, 12, 14, 15, 7, 1, 9, 2, 11, 5, 8, 10, 3, 0, 4, 6),
            byteArrayOf(9, 4, 12, 0, 8, 14, 1, 3, 11, 6, 5, 13, 10, 2, 15, 7),
            byteArrayOf(5, 12, 6, 15, 10, 13, 14, 3, 9, 8, 2, 11, 0, 1, 7, 4),
            byteArrayOf(1, 5, 7, 2, 6, 11, 4, 13, 3, 10, 0, 9, 15, 14, 12, 8),
            byteArrayOf(13, 0, 11, 15, 8, 4, 9, 3, 1, 5, 14, 10, 7, 2, 6, 12),
            byteArrayOf(9, 5, 6, 7, 3, 15, 14, 4, 2, 8, 13, 0, 1, 11, 10, 12),
            byteArrayOf(5, 14, 1, 2, 13, 0, 7, 12, 9, 11, 8, 4, 3, 10, 6, 15),
        )
        private val CONFIG_DECODE = arrayOf(
            byteArrayOf(11, 15, 6, 12, 5, 4, 1, 8, 13, 7, 3, 14, 0, 9, 2, 10),
            byteArrayOf(14, 4, 10, 7, 15, 13, 8, 5, 0, 2, 12, 6, 3, 11, 1, 9),
            byteArrayOf(9, 10, 15, 2, 0, 4, 1, 8, 13, 3, 7, 11, 5, 12, 6, 14),
            byteArrayOf(6, 0, 12, 3, 7, 5, 15, 2, 13, 10, 11, 4, 8, 1, 14, 9),
            byteArrayOf(4, 8, 13, 6, 5, 1, 3, 12, 11, 2, 7, 15, 14, 0, 10, 9),
            byteArrayOf(3, 7, 15, 11, 5, 9, 13, 12, 6, 0, 14, 10, 2, 1, 4, 8),
            byteArrayOf(7, 2, 8, 6, 11, 0, 1, 14, 15, 3, 9, 4, 13, 5, 12, 10),
            byteArrayOf(10, 0, 12, 5, 9, 13, 3, 14, 2, 4, 15, 6, 7, 8, 1, 11),
            byteArrayOf(4, 2, 5, 14, 1, 13, 7, 11, 10, 12, 6, 3, 9, 8, 0, 15),
            byteArrayOf(7, 6, 11, 15, 12, 5, 14, 3, 9, 10, 0, 8, 13, 1, 4, 2),
            byteArrayOf(7, 6, 15, 4, 8, 1, 0, 11, 9, 2, 3, 5, 13, 14, 10, 12),
            byteArrayOf(6, 10, 0, 4, 2, 9, 11, 3, 7, 13, 12, 15, 8, 1, 14, 5),
            byteArrayOf(2, 6, 9, 10, 4, 7, 1, 5, 8, 3, 13, 12, 15, 11, 0, 14),
            byteArrayOf(5, 12, 9, 11, 13, 7, 3, 14, 2, 6, 0, 10, 4, 8, 1, 15),
            byteArrayOf(1, 14, 8, 2, 6, 11, 0, 12, 3, 7, 9, 15, 10, 13, 5, 4),
            byteArrayOf(7, 14, 0, 8, 10, 13, 3, 6, 15, 5, 4, 11, 12, 2, 9, 1),
            byteArrayOf(11, 3, 5, 9, 10, 13, 8, 1, 2, 12, 7, 15, 6, 14, 0, 4),
            byteArrayOf(11, 3, 2, 9, 7, 5, 6, 4, 12, 14, 0, 8, 13, 15, 10, 1),
            byteArrayOf(15, 12, 9, 13, 10, 1, 2, 0, 3, 11, 8, 6, 14, 4, 5, 7),
            byteArrayOf(7, 9, 6, 0, 3, 14, 11, 10, 13, 8, 4, 12, 5, 15, 1, 2),
            byteArrayOf(13, 10, 12, 5, 15, 14, 1, 6, 3, 9, 7, 2, 8, 4, 11, 0),
            byteArrayOf(10, 3, 5, 8, 4, 2, 9, 15, 7, 11, 13, 0, 14, 6, 12, 1),
            byteArrayOf(1, 3, 14, 11, 15, 7, 9, 0, 8, 12, 13, 5, 4, 10, 6, 2),
            byteArrayOf(5, 10, 14, 0, 4, 9, 12, 8, 13, 6, 2, 7, 15, 11, 3, 1),
            byteArrayOf(6, 15, 8, 7, 2, 12, 3, 11, 1, 10, 9, 5, 4, 13, 14, 0),
            byteArrayOf(9, 8, 12, 3, 15, 14, 7, 4, 10, 6, 5, 0, 2, 13, 1, 11),
            byteArrayOf(14, 15, 7, 8, 11, 3, 9, 0, 1, 2, 5, 6, 4, 13, 12, 10),
            byteArrayOf(9, 10, 4, 5, 0, 14, 13, 6, 7, 12, 11, 8, 15, 2, 1, 3),
            byteArrayOf(0, 2, 12, 6, 10, 15, 3, 1, 9, 8, 4, 5, 14, 11, 7, 13),
            byteArrayOf(6, 7, 2, 14, 12, 9, 4, 15, 8, 11, 10, 5, 0, 13, 3, 1),
            byteArrayOf(10, 5, 12, 13, 6, 3, 15, 1, 0, 4, 9, 11, 2, 14, 8, 7),
            byteArrayOf(6, 4, 5, 12, 0, 7, 1, 15, 13, 11, 10, 14, 2, 8, 9, 3),
        )

        fun feBoundTransform(type: Int, input: ByteArray): ByteArray {
            val table = if (type == 1) CONFIG_ENCODE else CONFIG_DECODE
            val out = ByteArray(input.size)
            for (i in input.indices) {
                val b = input[i].toInt() and 0xff
                val i2 = i * 2
                val hi = table[i2 % 32][(b ushr 4) and 0xf].toInt()
                val lo = table[(i2 + 1) % 32][b and 0xf].toInt()
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }
    }
}
