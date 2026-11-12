package ru.gigapisar.model

object ModelConfig {
    const val DIRECTORY_NAME = "gigaam"
    const val MODEL_FILE_NAME = "v3_e2e_ctc.int8.onnx"
    const val VOCAB_FILE_NAME = "v3_e2e_ctc_vocab.txt"
    const val READY_FILE_NAME = ".ready"

    // Основное зеркало — своё: чужой репозиторий (и HF-аккаунт) может исчезнуть в любой момент.
    // Файл байт в байт совпадает с оригиналом (SHA-256 ниже), поэтому докачка, начатая с одного
    // зеркала, продолжается с другого. Hugging Face оставлен вторым: он быстрее GitHub CDN,
    // но зависит от чужого аккаунта. Третий — прежнее зеркало, на всякий случай.
    private const val GITHUB_OWN = "https://github.com/drts-sv/GigaAM/releases/download/model-v3/"
    private const val HUGGING_FACE = "https://huggingface.co/istupakov/gigaam-v3-onnx/resolve/main/"
    private const val GITHUB_OLD = "https://github.com/moznoazachem/giga-pisar-android/releases/download/model-v3/"

    val MODEL_URLS =
        listOf(
            GITHUB_OWN + MODEL_FILE_NAME,
            "$HUGGING_FACE$MODEL_FILE_NAME?download=true",
            GITHUB_OLD + MODEL_FILE_NAME,
        )

    val VOCAB_URLS =
        listOf(
            GITHUB_OWN + VOCAB_FILE_NAME,
            "$HUGGING_FACE$VOCAB_FILE_NAME?download=true",
            GITHUB_OLD + VOCAB_FILE_NAME,
        )

    const val EXPECTED_MODEL_SIZE = 224_893_347L
    const val MODEL_SHA256 =
        "2e3fcb7a7b66030336fd10c2fcfb033bd1dc7e1bf238fe5cfd83b1d0cfc9d28e"
    const val VOCAB_SHA256 =
        "142de7570b3de5b3035ce111a89c228e80e6085273731d944093ddf24fa539cd"
    const val VOCAB_SIZE = 257
    const val BLANK_ID = 256
}
