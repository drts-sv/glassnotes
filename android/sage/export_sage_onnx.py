#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Экспорт корректора SAGE (ai-forever/sage-fredt5-distilled-95m) в ONNX + int8.

Зачем: приложению GlasNote нужен офлайновый корректор русского текста (задача #78 —
«астцеллограф» → «осциллограф»). Модель от той же команды, что делает GigaAM (ai-forever, MIT).
Готового ONNX у авторов нет — делаем сами.

Запуск:
    C:/Users/drts/sage-onnx/venv/Scripts/python.exe android/sage/export_sage_onnx.py

Почему без optimum: в optimum 2.x экспортёр ONNX вынесен в отдельный пакет, а transformers 5.x
уже не в духе этой модели. Экспортируем T5 вручную — энкодер и декодер отдельно, это стандартная
схема и она не зависит от версий.

Что делает:
 1. качает модель и токенизатор (383 МБ, кэш HF);
 2. печатает тип токенизатора и файлы — от этого зависит перенос на телефон
    (BPE/vocab.json — реализуемо на Kotlin, SentencePiece/unigram — нужен JNI-порт);
 3. экспортирует энкодер и декодер в ONNX, квантует в int8;
 4. прогоняет фразы (включая «астцеллограф» и имя собственное), печатает результат и время;
 5. печатает размеры и sha256 — пригодятся приложению для проверки целостности.
"""

import hashlib
import os
import sys
import time

MODEL = "ai-forever/sage-fredt5-distilled-95m"
OUT = os.environ.get("SAGE_OUT", "C:/Users/drts/sage-onnx/out")

PHRASES = [
    "астцеллограф нужен для проверки сигнала",
    "я подключил малину через уарт и посмотрел осцилограмму",
    "нужно купить хлеб и молоко завтра",
    "отправить письмо константину сергеевичу про проект",
    "прошивка стм32 не собирается из-за ошибки в мейкфайле",
]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    import numpy as np
    import torch
    from transformers import AutoTokenizer, AutoModelForSeq2SeqLM

    os.makedirs(OUT, exist_ok=True)
    print("=== torch", torch.__version__, "| numpy", np.__version__)

    print("=== качаю токенизатор и модель (383 МБ, один раз):")
    t0 = time.time()
    tok = AutoTokenizer.from_pretrained(MODEL)
    model = AutoModelForSeq2SeqLM.from_pretrained(MODEL).eval()
    print("    готово за %.0f с" % (time.time() - t0))
    print("=== токенизатор:", type(tok).__name__, "| модель:", type(model).__name__,
          "| тип конфига:", model.config.model_type)
    print("=== файлы токенизатора в кэше HF:")
    try:
        from huggingface_hub import hf_hub_download
        for name in ("vocab.json", "merges.txt", "spiece.model", "tokenizer.json",
                     "tokenizer_config.json", "special_tokens_map.json"):
            try:
                p = hf_hub_download(MODEL, name)
                print("    %-24s %8.1f КБ" % (name, os.path.getsize(p) / 1024))
            except Exception:
                print("    %-24s нет" % name)
    except Exception as e:
        print("    (список файлов не получен:", e, ")")

    print("\n=== проверка на фразах (PyTorch, до экспорта):")
    results = []
    for phrase in PHRASES:
        t = time.time()
        inp = tok(phrase, max_length=None, padding="longest", truncation=False, return_tensors="pt")
        with torch.no_grad():
            out = model.generate(**inp, max_length=int(inp["input_ids"].size(1) * 1.5))
        text = tok.batch_decode(out, skip_special_tokens=True)[0]
        dt = time.time() - t
        results.append((phrase, text, dt))
        print("  было : %s\n  стало: %s\n  время: %.2f с\n" % (phrase, text, dt))

    enc_path = os.path.join(OUT, "sage_encoder.onnx")
    dec_path = os.path.join(OUT, "sage_decoder.onnx")

    print("=== экспорт энкодера в ONNX:")
    enc = model.get_encoder()
    pad = tok.pad_token_id if tok.pad_token_id is not None else 0
    dummy_ids = torch.tensor([[pad] * 8], dtype=torch.long)
    dummy_mask = torch.ones_like(dummy_ids)
    t0 = time.time()
    with torch.no_grad():
        torch.onnx.export(
            enc, (dummy_ids, dummy_mask), enc_path,
            input_names=["input_ids", "attention_mask"],
            output_names=["last_hidden_state"],
            dynamic_axes={"input_ids": {0: "b", 1: "n"}, "attention_mask": {0: "b", 1: "n"},
                          "last_hidden_state": {0: "b", 1: "n"}},
            opset_version=14, do_constant_folding=True)
    print("    %s — %.1f МБ за %.0f с" % (os.path.basename(enc_path),
                                          os.path.getsize(enc_path) / 1048576, time.time() - t0))

    print("=== экспорт декодера в ONNX (без прошлого контекста — для заметок хватает):")

    class DecoderWrap(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, input_ids, encoder_hidden_states, encoder_attention_mask):
            out = self.m(input_ids=input_ids,
                         encoder_hidden_states=encoder_hidden_states,
                         encoder_attention_mask=encoder_attention_mask,
                         use_cache=False)
            return out.logits

    dec = DecoderWrap(model)
    with torch.no_grad():
        hidden = enc(dummy_ids, dummy_mask)[0]
        dummy_dec = torch.tensor([[model.config.decoder_start_token_id]], dtype=torch.long)
        t0 = time.time()
        torch.onnx.export(
            dec, (dummy_dec, hidden, dummy_mask), dec_path,
            input_names=["input_ids", "encoder_hidden_states", "encoder_attention_mask"],
            output_names=["logits"],
            dynamic_axes={"input_ids": {0: "b", 1: "d"},
                          "encoder_hidden_states": {0: "b", 1: "n"},
                          "encoder_attention_mask": {0: "b", 1: "n"},
                          "logits": {0: "b", 1: "d"}},
            opset_version=14, do_constant_folding=True)
    print("    %s — %.1f МБ за %.0f с" % (os.path.basename(dec_path),
                                          os.path.getsize(dec_path) / 1048576, time.time() - t0))

    print("\n=== квантование в int8:")
    from onnxruntime.quantization import quantize_dynamic, QuantType
    quantized = []
    for p in (enc_path, dec_path):
        q = p.replace(".onnx", ".int8.onnx")
        t0 = time.time()
        quantize_dynamic(p, q, weight_type=QuantType.QInt8)
        quantized.append(q)
        print("    %-32s %7.1f МБ (%.0f с)" % (os.path.basename(q), os.path.getsize(q) / 1048576,
                                               time.time() - t0))

    print("\n=== проверка int8 через onnxruntime (порядок скорости на телефоне):")
    import onnxruntime as ort
    so = ort.SessionOptions()
    so.intra_op_num_threads = 4
    enc_s = ort.InferenceSession(os.path.join(OUT, "sage_encoder.int8.onnx"), so,
                                 providers=["CPUExecutionProvider"])
    dec_s = ort.InferenceSession(os.path.join(OUT, "sage_decoder.int8.onnx"), so,
                                 providers=["CPUExecutionProvider"])
    start_id = model.config.decoder_start_token_id
    eos_id = model.config.eos_token_id
    for phrase, expected, _dt in results:
        t = time.time()
        inp = tok(phrase, max_length=None, padding="longest", truncation=False, return_tensors="np")
        ids = inp["input_ids"].astype(np.int64)
        mask = inp["attention_mask"].astype(np.int64)
        hidden = enc_s.run(None, {"input_ids": ids, "attention_mask": mask})[0]
        dec_ids = np.array([[start_id]], dtype=np.int64)
        for _step in range(int(ids.shape[1] * 1.5) + 2):
            logits = dec_s.run(None, {"input_ids": dec_ids,
                                      "encoder_hidden_states": hidden,
                                      "encoder_attention_mask": mask})[0]
            nxt = int(np.argmax(logits[0, -1, :]))
            dec_ids = np.concatenate([dec_ids, np.array([[nxt]], dtype=np.int64)], axis=1)
            if nxt == eos_id:
                break
        text = tok.decode(dec_ids[0][1:], skip_special_tokens=True)
        print("  было : %s\n  стало: %s\n  время int8: %.2f с\n" % (phrase, text, time.time() - t))

    print("=== итог: файлы и sha256")
    for q in quantized:
        print("    %-32s %7.2f МБ  %s" % (os.path.basename(q), os.path.getsize(q) / 1048576, sha256(q)[:16]))


if __name__ == "__main__":
    sys.exit(main())
