// Google Apps Script - 處理碳排放資料與圖片
// 部署為 Web 應用程式，存取權限設為「任何人」

function doPost(e) {
  try {
    var sheet = SpreadsheetApp.getActiveSpreadsheet().getActiveSheet();
    var data = JSON.parse(e.postData.contents);
    
    // 🔹 處理圖片上傳
    if (data.imageBase64) {
      var name = data.name || "未知使用者";
      
      // 檢查使用者是否已存在
      var lastRow = sheet.getLastRow();
      var found = false;
      
      for (var i = 2; i <= lastRow; i++) {
        var existingName = sheet.getRange(i, 1).getValue();
        if (existingName == name) {
          // 更新現有使用者的圖片（C 欄）
          sheet.getRange(i, 3).setValue(data.imageBase64);
          found = true;
          Logger.log("更新使用者圖片: " + name);
          break;
        }
      }
      
      // 如果找不到，新增一列
      if (!found) {
        sheet.appendRow([name, "", data.imageBase64]);
        Logger.log("新增使用者圖片: " + name);
      }
      
      return ContentService.createTextOutput("圖片上傳成功");
    }
    
    // 🔹 處理一般資料上傳
    else if (data.name && data.total) {
      var name = data.name;
      var total = parseFloat(data.total);
      
      // 檢查使用者是否已存在
      var lastRow = sheet.getLastRow();
      var found = false;
      
      for (var i = 2; i <= lastRow; i++) {
        var existingName = sheet.getRange(i, 1).getValue();
        if (existingName == name) {
          // 更新現有使用者的碳排放量（B 欄）
          sheet.getRange(i, 2).setValue(total);
          found = true;
          Logger.log("更新使用者資料: " + name + " = " + total);
          break;
        }
      }
      
      // 如果找不到，新增一列
      if (!found) {
        sheet.appendRow([name, total, ""]);
        Logger.log("新增使用者資料: " + name + " = " + total);
      }
      
      return ContentService.createTextOutput("資料上傳成功");
    }
    
    return ContentService.createTextOutput("缺少必要參數");
    
  } catch (error) {
    Logger.log("Error: " + error);
    return ContentService.createTextOutput("Error: " + error.toString());
  }
}

function doGet(e) {
  try {
    var sheet = SpreadsheetApp.getActiveSpreadsheet().getActiveSheet();
    var lastRow = sheet.getLastRow();
    
    // 檢查是否需要包含圖片
    var includeImages = e.parameter.includeImages === "true";
    
    var dataArray = [];
    
    // 從第 2 列開始讀取（跳過標題）
    for (var i = 2; i <= lastRow; i++) {
      var name = sheet.getRange(i, 1).getValue(); // A 欄：姓名
      var total = sheet.getRange(i, 2).getValue(); // B 欄：碳排放量
      
      if (name) {
        var record = {
          "name": name,
          "total": total.toString()
        };
        
        // 🔹 如果要求包含圖片，則讀取 C 欄
        if (includeImages) {
          var imageBase64 = sheet.getRange(i, 3).getValue(); // C 欄：Base64 圖片
          record.image = imageBase64 || ""; // 如果沒有圖片就回傳空字串
        }
        
        dataArray.push(record);
      }
    }
    
    // 回傳 JSON
    return ContentService
      .createTextOutput(JSON.stringify(dataArray))
      .setMimeType(ContentService.MimeType.JSON);
    
  } catch (error) {
    Logger.log("Error in doGet: " + error);
    return ContentService
      .createTextOutput(JSON.stringify([]))
      .setMimeType(ContentService.MimeType.JSON);
  }
}

// 測試函數（可選）
function testReadData() {
  var result = doGet({parameter: {includeImages: "true"}});
  Logger.log(result.getContent());
}