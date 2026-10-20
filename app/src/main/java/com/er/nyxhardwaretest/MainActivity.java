package com.er.nyxhardwaretest;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.*;
import android.graphics.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.print.*;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.*;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class MainActivity extends ComponentActivity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private BarcodeScanner scanner;
    private PreviewView camera;
    private LinearLayout root;
    private TextView status;
    private DB db;
    private boolean scanning=false, busy=false;
    private String hu="", np="";
    private long lastRead=0;
    private int qrPage=0;
    private ToneGenerator tone;
    private static final Pattern MOLD=Pattern.compile("(?<![A-Z0-9])CFM[0-9]{9,10}(?![A-Z0-9])");
    private static final Pattern ASSEMBLY=Pattern.compile("(?<![0-9])5[0-9]{8}(?![0-9])");
    private static final Pattern PART=Pattern.compile("(?<![0-9])[0-9]{10}(?![0-9])");
    @Override public void onCreate(Bundle b){super.onCreate(b);db=new DB();scanner=BarcodeScanning.getClient();tone=new ToneGenerator(AudioManager.STREAM_MUSIC,80);showHome();}
    private TextView txt(String t,int size){TextView v=new TextView(this);v.setText(t);v.setTextSize(size);v.setTextColor(0xff183047);v.setPadding(12,12,12,12);return v;}
    private Button btn(String t,Runnable r){Button b=new Button(this);b.setText(t);b.setOnClickListener(v->r.run());return b;}
    private LinearLayout layout(String title){LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(12,12,12,12);l.setBackgroundColor(0xfff4f7fa);l.addView(txt(title,22));return l;}
    private void stopCamera(){scanning=false;try{ProcessCameraProvider.getInstance(this).addListener(()->{try{ProcessCameraProvider.getInstance(this).get().unbindAll();}catch(Exception ignored){}},ContextCompat.getMainExecutor(this));}catch(Exception ignored){}}
    private void showHome(){stopCamera();root=layout("SCANNER YCC4 · V5");root.addView(btn("ESCANEAR HU + NP",this::showCamera));root.addView(btn("COMPARTIR MATRIZ",this::share));root.addView(btn("EXPORTAR CSV",this::saveCsv));root.addView(btn("CÓDIGOS QR (20 POR PÁGINA)",()->showQR(0)));root.addView(txt("REGISTROS: "+db.total(),18));ScrollView scroll=new ScrollView(this);LinearLayout lines=new LinearLayout(this);lines.setOrientation(1);scroll.addView(lines);for(String[] a:db.rows()){lines.addView(txt(a[0]+"    "+a[1],15));}root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);}
    private void showCamera(){scanning=true;hu="";np="";root=layout("LECTURA DE ETIQUETAS");root.addView(btn("← REGRESAR",this::showHome));camera=new PreviewView(this);root.addView(camera,new LinearLayout.LayoutParams(-1,0,1));status=txt("Buscando HU y NP en códigos de barras...",17);root.addView(status);setContentView(root);if(android.os.Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},10);else startCamera();}
    @Override public void onRequestPermissionsResult(int code,String[] p,int[] g){super.onRequestPermissionsResult(code,p,g);if(code==10&&g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)startCamera();else if(status!=null)status.setText("Permiso de cámara requerido");}
    private void startCamera(){ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);future.addListener(()->{try{ProcessCameraProvider provider=future.get();provider.unbindAll();Preview preview=new Preview.Builder().build();preview.setSurfaceProvider(camera.getSurfaceProvider());ImageAnalysis analysis=new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();analysis.setAnalyzer(worker,this::analyze);provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,analysis);}catch(Exception e){status.setText("Error de cámara: "+e.getMessage());}},ContextCompat.getMainExecutor(this));}
    @androidx.camera.core.ExperimentalGetImage private void analyze(ImageProxy frame){if(!scanning||busy||System.currentTimeMillis()-lastRead<180||frame.getImage()==null){frame.close();return;}busy=true;lastRead=System.currentTimeMillis();InputImage image=InputImage.fromMediaImage(frame.getImage(),frame.getImageInfo().getRotationDegrees());scanner.process(image).addOnCompleteListener(task->{ArrayList<String> values=new ArrayList<>();if(task.isSuccessful())for(Barcode b:task.getResult())if(b.getRawValue()!=null)values.add(b.getRawValue());ui.post(()->{if(scanning)accept(values);busy=false;frame.close();});});}
    private String find(Pattern pattern,String value){Matcher m=pattern.matcher(value);return m.find()?m.group():"";}
    private void accept(ArrayList<String> values){
        // Keep HU/NP pairing within one camera session. Never infer NP from OCR.
        String candidateHu="",candidateNp="";
        for(String original:values){String s=original.toUpperCase(Locale.ROOT).trim();String h=find(MOLD,s);if(h.isEmpty())h=find(ASSEMBLY,s);if(!h.isEmpty()){candidateHu=h;continue;}
            // NP may be preceded by P or 1P; never treat the HU as an NP.
            String n=find(PART,s);if(!n.isEmpty()&&!s.matches("5[0-9]{8}"))candidateNp=n;
        }
        if(!candidateHu.isEmpty()&&!candidateHu.equals(hu)){hu=candidateHu;np="";}
        if(!candidateNp.isEmpty())np=candidateNp;
        if(!hu.isEmpty()&&!np.isEmpty()){
            if(db.add(hu,np)){tone.startTone(ToneGenerator.TONE_PROP_BEEP,150);status.setText("✓ GUARDADO: "+hu+"  "+np+"\nRegistros: "+db.total());}
            else {tone.startTone(ToneGenerator.TONE_PROP_NACK,220);status.setText("HU DUPLICADA: "+hu);}
            hu="";np="";
        }else status.setText("HU: "+(hu.isEmpty()?"—":hu)+"\nNP: "+(np.isEmpty()?"—":np)+"\nAcerque los códigos a la cámara");
    }
    private String matrix(java.util.List<String[]> rows){StringBuilder b=new StringBuilder();for(String[] row:rows){if(b.length()>0)b.append('\n');b.append(row[0]).append('\t').append(row[1]);}return b.toString();}
    private void share(){String data=matrix(db.rows());if(data.isEmpty()){toast("No hay registros");return;}Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,data);startActivity(Intent.createChooser(i,"Compartir matriz HU / NP"));}
    private void saveCsv(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/csv");i.putExtra(Intent.EXTRA_TITLE,"Scanner_YCC4_"+new SimpleDateFormat("yyyyMMdd_HHmm",Locale.US).format(new Date())+".csv");startActivityForResult(i,42);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=42||result!=RESULT_OK||data==null||data.getData()==null)return;try(OutputStream out=getContentResolver().openOutputStream(data.getData())){StringBuilder csv=new StringBuilder("HU,NP\r\n");for(String[] r:db.rows())csv.append(r[0]).append(',').append(r[1]).append("\r\n");out.write(csv.toString().getBytes(StandardCharsets.UTF_8));toast("CSV guardado");}catch(Exception e){toast("Error CSV: "+e.getMessage());}}
    private void showQR(int requested){stopCamera();java.util.List<String[]> rows=db.rows();if(rows.isEmpty()){toast("No hay registros");showHome();return;}int pages=(rows.size()+19)/20;qrPage=Math.max(0,Math.min(requested,pages-1));int from=qrPage*20,to=Math.min(from+20,rows.size());String payload=matrix(rows.subList(from,to));root=layout("QR "+(qrPage+1)+" / "+pages);root.addView(btn("← REGRESAR",this::showHome));Bitmap qr=makeQR(payload,800);if(qr==null){root.addView(txt("No fue posible generar el QR",16));}else{ImageView image=new ImageView(this);image.setImageBitmap(qr);image.setAdjustViewBounds(true);root.addView(image,new LinearLayout.LayoutParams(-1,0,1));}root.addView(txt("Registros "+(from+1)+"–"+to+" de "+rows.size(),16));LinearLayout navigation=new LinearLayout(this);navigation.setOrientation(0);Button back=btn("◀ ANTERIOR",()->showQR(qrPage-1));back.setEnabled(qrPage>0);navigation.addView(back,new LinearLayout.LayoutParams(0,-2,1));Button next=btn("SIGUIENTE ▶",()->showQR(qrPage+1));next.setEnabled(qrPage<pages-1);navigation.addView(next,new LinearLayout.LayoutParams(0,-2,1));root.addView(navigation);root.addView(btn("IMPRIMIR ESTE QR",()->printQR(payload,qrPage+1,pages)));root.addView(btn("IMPRIMIR TODOS LOS QR",this::printAll));setContentView(root);}
    private Bitmap makeQR(String content,int size){try{Map<EncodeHintType,Object> hints=new HashMap<>();hints.put(EncodeHintType.CHARACTER_SET,"UTF-8");hints.put(EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.M);hints.put(EncodeHintType.MARGIN,2);BitMatrix bits=new QRCodeWriter().encode(content,BarcodeFormat.QR_CODE,size,size,hints);Bitmap b=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);for(int y=0;y<size;y++)for(int x=0;x<size;x++)b.setPixel(x,y,bits.get(x,y)?Color.BLACK:Color.WHITE);return b;}catch(Exception e){return null;}}
    private void printQR(String payload,int page,int total){ArrayList<String> items=new ArrayList<>();items.add(payload);printPages(items,page,total);}
    private void printAll(){java.util.List<String[]> rows=db.rows();ArrayList<String> items=new ArrayList<>();for(int i=0;i<rows.size();i+=20)items.add(matrix(rows.subList(i,Math.min(i+20,rows.size()))));printPages(items,1,items.size());}
    private void printPages(ArrayList<String> items,int start,int total){PrintManager manager=(PrintManager)getSystemService(PRINT_SERVICE);manager.print("Scanner_YCC4_QR",new QRPrintAdapter(items,start,total),new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A6).setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME).build());}
    private class QRPrintAdapter extends PrintDocumentAdapter {final ArrayList<String> items;final int start,total;QRPrintAdapter(ArrayList<String> items,int start,int total){this.items=items;this.start=start;this.total=total;}
        @Override public void onLayout(PrintAttributes oldA,PrintAttributes newA,android.os.CancellationSignal signal,LayoutResultCallback cb,Bundle extras){android.print.PrintDocumentInfo info=new android.print.PrintDocumentInfo.Builder("Scanner_YCC4_QR.pdf").setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(items.size()).build();cb.onLayoutFinished(info,true);}
        @Override public void onWrite(android.print.PageRange[] ranges,android.os.ParcelFileDescriptor dest,android.os.CancellationSignal signal,WriteResultCallback cb){android.graphics.pdf.PdfDocument pdf=new android.graphics.pdf.PdfDocument();for(int i=0;i<items.size();i++){android.graphics.pdf.PdfDocument.PageInfo info=new android.graphics.pdf.PdfDocument.PageInfo.Builder(595,842,i+1).create();android.graphics.pdf.PdfDocument.Page page=pdf.startPage(info);Canvas c=page.getCanvas();c.drawColor(Color.WHITE);Paint p=new Paint(3);p.setColor(Color.BLACK);p.setTextSize(22);c.drawText("SCANNER YCC4 · QR "+(start+i)+" / "+total,45,65,p);Bitmap qr=makeQR(items.get(i),900);if(qr!=null)c.drawBitmap(qr,null,new Rect(45,110,550,615),p);p.setTextSize(15);c.drawText("HU / NP · "+items.get(i).split("\n").length+" registros",45,655,p);pdf.finishPage(page);}try(OutputStream out=new FileOutputStream(dest.getFileDescriptor())){pdf.writeTo(out);cb.onWriteFinished(new android.print.PageRange[]{android.print.PageRange.ALL_PAGES});}catch(Exception e){cb.onWriteFailed(e.getMessage());}finally{pdf.close();}}
    }
    private void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_LONG).show();}
    @Override public void onBackPressed(){if(scanning)showHome();else super.onBackPressed();}
    @Override protected void onDestroy(){stopCamera();scanner.close();tone.release();worker.shutdown();db.close();super.onDestroy();}
    private class DB extends SQLiteOpenHelper {DB(){super(MainActivity.this,"etiquetas_v1.db",null,1);} @Override public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE etiquetas (id INTEGER PRIMARY KEY AUTOINCREMENT,hu TEXT NOT NULL UNIQUE,np TEXT NOT NULL,dado TEXT NOT NULL,maquina TEXT NOT NULL,cantidad INTEGER NOT NULL,fecha TEXT NOT NULL,escaneado TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");} @Override public void onUpgrade(SQLiteDatabase d,int a,int b){}
        boolean add(String hu,String np){android.content.ContentValues v=new android.content.ContentValues();v.put("hu",hu);v.put("np",np);v.put("dado","");v.put("maquina","");v.put("cantidad",0);v.put("fecha","");return getWritableDatabase().insertWithOnConflict("etiquetas",null,v,SQLiteDatabase.CONFLICT_IGNORE)!=-1;}
        java.util.List<String[]> rows(){ArrayList<String[]> rows=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT hu,np FROM etiquetas ORDER BY id",null)){while(c.moveToNext())rows.add(new String[]{c.getString(0),c.getString(1)});}return rows;}
        int total(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM etiquetas",null)){return c.moveToFirst()?c.getInt(0):0;}}
    }
}
