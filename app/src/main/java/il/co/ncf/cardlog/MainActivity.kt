package il.co.ncf.cardlog

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import android.content.ClipData
import android.content.ClipboardManager

class MainActivity : Activity(), NfcAdapter.ReaderCallback {
 private val bg=Color.rgb(17,27,43); private val panel=Color.rgb(30,44,64); private val mint=Color.rgb(101,214,196); private val white=Color.rgb(238,244,250)
 private var nfc:NfcAdapter?=null; private lateinit var status:TextView; private lateinit var rows:LinearLayout; private val log=StringBuilder()
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE);window.statusBarColor=bg;window.navigationBarColor=bg;nfc=NfcAdapter.getDefaultAdapter(this)
 if(!getPreferences(0).getBoolean("consent",false)){val l=LinearLayout(this).apply{orientation=1;gravity=Gravity.CENTER;setPadding(24,24,24,24);setBackgroundColor(bg)};l.addView(txt("קורא יומן כרטיס",27,mint));l.addView(txt("אני מאשר שהכרטיס שייקרא הוא שלי",18,white));l.addView(btn("אני מאשר וממשיך").apply{setOnClickListener{getPreferences(0).edit().putBoolean("consent",true).apply();main();nfc?.enableReaderMode(this@MainActivity,this@MainActivity,NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,null)}});setContentView(l)}else main()}
 private fun main(){val outer=LinearLayout(this).apply{orientation=1;setPadding(16,16,16,16);setBackgroundColor(bg)};val sc=ScrollView(this);val c=LinearLayout(this).apply{orientation=1};c.addView(txt("קורא יומן כרטיס",27,white));c.addView(txt("קריאה מקומית דרך NFC · ללא אינטרנט",13,mint));val hero=LinearLayout(this).apply{orientation=1;gravity=Gravity.CENTER;setPadding(16,24,16,24);setBackgroundColor(panel)};hero.addView(txt(")))",42,mint));hero.addView(txt("הצמד את הכרטיס לגב המכשיר",18,white));hero.addView(txt("החזק יציב עד לסיום הקריאה",13,white));c.addView(hero)
 status=txt(if(nfc==null)"במכשיר זה אין NFC" else if(nfc?.isEnabled==false)"NFC כבוי" else "מוכן לקריאה. הצמד כרטיס EMV.",15,white);c.addView(status)
 if(nfc!=null&&nfc?.isEnabled==false)c.addView(btn("פתיחת הגדרות NFC").apply{setOnClickListener{startActivity(Intent(Settings.ACTION_NFC_SETTINGS))}})
 rows=LinearLayout(this).apply{orientation=1};c.addView(rows);c.addView(btn("אבחון").apply{setOnClickListener{diagnostics()}});sc.addView(c);outer.addView(sc);setContentView(outer)}
 override fun onResume(){super.onResume();if(getPreferences(0).getBoolean("consent",false))nfc?.enableReaderMode(this,this,NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,null)}
 override fun onPause(){try{nfc?.disableReaderMode(this)}catch(_:Exception){};super.onPause()}
 override fun onTagDiscovered(tag:Tag){runOnUiThread{status.text="הכרטיס זוהה, קורא…";rows.removeAllViews();log.setLength(0)}
 val iso=IsoDep.get(tag);if(iso==null){runOnUiThread{status.text="סוג כרטיס לא נתמך"};return}
 try{iso.connect();iso.timeout=4000
 val ppse=send(iso,byteArrayOf(0,0xA4.toByte(),4,0,14,*"2PAY.SYS.DDF01".toByteArray(),0));val aids=find(ppse,"4F").distinct()
 if(aids.isEmpty())throw Exception("not EMV")
 var total=0
 for(aid in aids.take(10)){val fci=send(iso,byteArrayOf(0,0xA4.toByte(),4,0,aid.size.toByte(),*aid,0));var le=find(fci,"9F4D").firstOrNull()
 if(le==null){val gd=send(iso,hx("80CA9F4D00"));le=if(ok(gd))find(gd,"9F4D").firstOrNull()else null}
 if(le==null||le.size<2)continue
 val sfi=le[0].toInt()and 255;val count=(le[1].toInt()and 255).coerceAtMost(25);if(sfi !in 1..30||count==0)continue
 val fmt=send(iso,hx("80CA9F4F00"));val tags=find(fmt,"9F4F").firstOrNull()?.let{format(it)}?: emptyList()
 val found=mutableListOf<Pair<Long,Map<String,ByteArray>>>()
 for(r in 1..count){var resp=send(iso,byteArrayOf(0,0xB2.toByte(),r.toByte(),((sfi shl 3)or 4).toByte(),0))
 if(resp.size>=2&&(resp[resp.size-2].toInt()and 255)==0x6C)resp=send(iso,byteArrayOf(0,0xB2.toByte(),r.toByte(),((sfi shl 3)or 4).toByte(),resp.last()))
 if(resp.size<2)continue;val sw1=resp[resp.size-2].toInt()and 255;val sw2=resp.last().toInt()and 255;if(sw1==0x6A&&(sw2==0x83||sw2==0x82))break;if(sw1!=0x90||sw2!=0)continue
 val body=resp.copyOf(resp.size-2);val values=if(tags.isEmpty())fields(body)else decode(body,tags);val atc=values["9F36"]?.fold(0L){a,b->(a shl 8)or(b.toLong()and 255)}?:r.toLong();found.add(atc to values)}
 if(found.isNotEmpty()){total+=found.size;runOnUiThread{rows.addView(txt("יומן אפליקציה",17,mint));found.sortedByDescending{it.first}.forEach{rows.addView(tx(it.first,it.second))}}}
 }
 iso.close();runOnUiThread{if(total==0)status.text="הכרטיס הזה לא שומר היסטוריית עסקאות על השבב" else status.text="נמצאו $total רשומות. הנתונים נשארים בזיכרון בלבד"}
 }catch(e:Exception){try{iso.close()}catch(_:Exception){};runOnUiThread{status.text=if(e.javaClass.simpleName.contains("TagLost"))"הכרטיס זז. הצמד שוב ונסה." else "לא ניתן לקרוא. הצמד שוב; ייתכן שהכרטיס אינו תומך ביומן."}}}
 private fun tx(atc:Long,v:Map<String,ByteArray>):LinearLayout{val box=LinearLayout(this).apply{orientation=1;setPadding(14,12,14,12);setBackgroundColor(panel)};val amount=v["9F02"]?.let{money(it,v["5F2A"])}?:"סכום לא זמין";box.addView(txt(amount,22,mint));box.addView(txt("תאריך: "+(v["9A"]?.let{it.joinToString(""){"%02X".format(it)}}?:"לא זמין")+"   שעה: "+(v["9F21"]?.let{it.joinToString(""){"%02X".format(it)}}?:""),14,white));box.addView(txt("מספר עסקה (ATC): "+(v["9F36"]?.let{it.joinToString(""){"%02X".format(it)}}?:atc.toString()),13,white));v["9F4E"]?.let{box.addView(txt("בית עסק: "+String(it,Charsets.US_ASCII).filter{ch->ch.isLetterOrDigit()||ch.isWhitespace()}.take(40),13,white))};box.layoutParams=LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=10};return box}
 private fun money(b:ByteArray,c:ByteArray?):String{val d=b.joinToString(""){"%02X".format(it)};val n=if(d.length>2)d.dropLast(2)+"."+d.takeLast(2)else d;return when(c?.joinToString(""){"%02X".format(it)}){"0376"->"₪ $n";"0840"->"$ $n";"0978"->"€ $n";else->n}}
 private fun send(i:IsoDep,b:ByteArray):ByteArray{val r=i.transceive(b);log.append(">> ").append(b.hex()).append("\n<< ").append(r.hex()).append("\n");return r}
 private fun ByteArray.hex()=joinToString(" "){"%02X".format(it)}
 private fun hx(s:String)=s.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
 private fun ok(b:ByteArray)=b.size>=2&&(b[b.size-2].toInt()and 255)==0x90&&(b.last().toInt()and 255)==0
 private fun find(d:ByteArray,w:String):List<ByteArray>{val out=mutableListOf<ByteArray>();var i=0;while(i<d.size-1){val start=i;var t=d[i++].toInt()and 255;if(t and 31==31&&i<d.size){t=(t shl 8)or(d[i++].toInt()and 255);if(t and 128==128&&i<d.size)t=(t shl 8)or(d[i++].toInt()and 255)};if(i>=d.size)break;var n=d[i++].toInt()and 255;if(n and 128!=0){val k=n and 127;if(k==0||k>3||i+k>d.size){i=start+1;continue};n=0;repeat(k){n=(n shl 8)or(d[i++].toInt()and 255)}};if(i+n>d.size){i=start+1;continue};val h=when{t>65535->"%06X".format(t);t>255->"%04X".format(t);else->"%02X".format(t)};if(h==w)out.add(d.copyOfRange(i,i+n));i+=n};return out}
 private fun fields(d:ByteArray):Map<String,ByteArray>{val keys=listOf("9A","9F21","9F02","9F03","5F2A","9F1A","9C","9F36","9F4E");return keys.mapNotNull{k->find(d,k).firstOrNull()?.let{k to it}}.toMap()}
 private fun format(d:ByteArray):List<Pair<String,Int>>{val out=mutableListOf<Pair<String,Int>>();var i=0;while(i<d.size){var t=d[i++].toInt()and 255;if(t and 31==31&&i<d.size){t=(t shl 8)or(d[i++].toInt()and 255);if(t and 128==128&&i<d.size)t=(t shl 8)or(d[i++].toInt()and 255)};if(i>=d.size)break;val n=d[i++].toInt()and 255;out.add((when{t>65535->"%06X".format(t);t>255->"%04X".format(t);else->"%02X".format(t)}) to n)};return out}
 private fun decode(d:ByteArray,tags:List<Pair<String,Int>>):Map<String,ByteArray>{val m=linkedMapOf<String,ByteArray>();var p=0;for((t,n)in tags){if(p+n>d.size)break;m[t]=d.copyOfRange(p,p+n);p+=n};return m}
 private fun diagnostics(){val l=LinearLayout(this).apply{orientation=1;setPadding(16,16,16,16);setBackgroundColor(bg)};l.addView(txt("אבחון APDU",21,mint));val t=txt(if(log.isEmpty())"עדיין לא בוצעה קריאה" else log.toString(),12,white);l.addView(ScrollView(this).apply{addView(t)},LinearLayout.LayoutParams(-1,0,1f));l.addView(btn("העתק אבחון").apply{setOnClickListener{(getSystemService(CLIPBOARD_SERVICE)as ClipboardManager).setPrimaryClip(ClipData.newPlainText("APDU",log.toString()));Toast.makeText(this@MainActivity,"הועתק",Toast.LENGTH_SHORT).show()}});setContentView(l)}
 private fun txt(s:String,size:Int,color:Int)=TextView(this).apply{text=s;textSize=size.toFloat();setTextColor(color);gravity=Gravity.CENTER_VERTICAL;setPadding(8,9,8,9)}
 private fun btn(s:String)=Button(this).apply{text=s;textSize=15f;minHeight=(48*resources.displayMetrics.density).toInt();isAllCaps=false;setTextColor(bg);setBackgroundColor(mint)}
}