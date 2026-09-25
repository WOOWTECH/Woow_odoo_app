# 父代理限定修正：取證保存不可掩蓋原輸入錯誤

對應review `31da3472-ecb4-451b-93a7-52e1c9fdd719` 的P2。僅新round8副本，原工具／所有runtime歷史不改。

- `ui_driver.py` failure observation的finally save改為best-effort；Exception另存in-memory `failureObservation.saveError`，不替代原本SET_TEXT timeout/raw mismatch。
- `test_input_budget.py`新增2項，確認原exception object identity保留，以及capture+save都失敗仍保留原raw-mismatch，無重送、無input PASS、stop已latch。
- 初次harness檢查雖111個unittest皆通過，但擋到一筆舊wrong-locale fixture的未mock socket probe；**socket建立被禁止，未發生真網路操作**。初次log/summary與stack已保留，沒有把harness failure藏掉。
- `test_system_ui_wait.py`僅為該舊fixture明確mock port absent，新增run未被呼叫的斷言；原wrong-locale拒絕斷言保留。
- 最終有真操作封鎖的`run_offline_tests.py`：**111 PASS／0 FAIL／0 ERROR／0 SKIP；forbiddenRealOperations=[]**。此為offline工具測試，不是普通UI。

`incremental.patch`包含相對原round8的三檔小增量；16個原Python檔雜湊皆未變。新harness單獨保存。未build、啟動AVD/ADB、輸入、操作網路或真signal，產品未改、無staged。

仍待新runtime批准；原輸入是否套用未知與guest cleanup unknown不因本修正而變成PASS。原始錯誤和保存錯誤會分開紀錄；若磁碟確實不可寫，記憶體備註不代表已落盤。
