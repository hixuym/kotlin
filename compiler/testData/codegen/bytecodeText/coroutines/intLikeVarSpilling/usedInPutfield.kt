// WITH_RUNTIME
class Controller {
    suspend fun suspendHere(x: Continuation<Unit>) {
        x.resume(Unit)
    }
}

fun builder(coroutine c: Controller.() -> Continuation<Unit>) {
    c(Controller()).resume(Unit)
}

@JvmField
var booleanResult = false
@JvmField
var charResult: Char = '0'
@JvmField
var byteResult: Byte = 0
@JvmField
var shortResult: Short = 0
@JvmField
var intResult: Int = 0

fun box(): String {
    builder {
        val x = true
        suspendHere()
        booleanResult = x
    }

    if (!booleanResult) return "fail 1"

    builder {
        val x = '1'
        suspendHere()
        charResult = x
    }

    if (charResult != '1') return "fail 2"

    builder {
        val x: Byte = 1
        suspendHere()
        byteResult = x
    }

    if (byteResult != 1.toByte()) return "fail 3"

    builder {
        val x: Short = 1
        suspendHere()
        shortResult = x
    }

    if (shortResult != 1.toShort()) return "fail 4"

    builder {
        val x: Int = 1
        suspendHere()
        intResult = x
    }

    if (intResult != 1) return "fail 5"
    return "OK"
}

// 1 PUTFIELD .*\.B\$0 : B
// 1 PUTFIELD .*\.C\$0 : C
// 1 PUTFIELD .*\.S\$0 : S
// 1 PUTFIELD .*\.Z\$0 : Z
// 1 PUTFIELD .*\.I\$0 : I
