package kr.neolab.sdk.pen.bluetooth.comm;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.Queue;

import kr.neolab.sdk.ink.structure.DotType;
import kr.neolab.sdk.ink.structure.Stroke;
import kr.neolab.sdk.pen.bluetooth.BTLEAdt;
import kr.neolab.sdk.pen.bluetooth.IConnectedThread;
import kr.neolab.sdk.pen.bluetooth.cmd.CommandManager;
import kr.neolab.sdk.pen.bluetooth.cmd.FwUpgradeCommand20;
import kr.neolab.sdk.pen.bluetooth.cmd.SetTimeCommand;
import kr.neolab.sdk.pen.bluetooth.lib.ByteConverter;
import kr.neolab.sdk.pen.bluetooth.lib.CMD20;
import kr.neolab.sdk.pen.bluetooth.lib.Chunk;
import kr.neolab.sdk.pen.bluetooth.lib.Packet;
import kr.neolab.sdk.pen.bluetooth.lib.PenProfile;
import kr.neolab.sdk.pen.bluetooth.lib.ProtocolParser20;
import kr.neolab.sdk.pen.bluetooth.lib.ProtocolParser20.IParsedPacketListener;
import kr.neolab.sdk.pen.filter.Fdot;
import kr.neolab.sdk.pen.filter.FilterForFilm;
import kr.neolab.sdk.pen.filter.FilterForPaper;
import kr.neolab.sdk.pen.filter.IFilterListener;
import kr.neolab.sdk.pen.offline.OfflineByteData;
import kr.neolab.sdk.pen.offline.OfflineByteParser;
import kr.neolab.sdk.pen.penmsg.JsonTag;
import kr.neolab.sdk.pen.penmsg.PenMsg;
import kr.neolab.sdk.pen.penmsg.PenMsgType;
import kr.neolab.sdk.util.NLog;
import kr.neolab.sdk.util.UseNoteData;

import static kr.neolab.sdk.pen.bluetooth.lib.PenProfile.KEY_DEFAULT_CALIBRATION;
import static kr.neolab.sdk.pen.bluetooth.lib.PenProfile.PROFILE_CREATE;
import static kr.neolab.sdk.pen.bluetooth.lib.PenProfile.PROFILE_DELETE;
import static kr.neolab.sdk.pen.bluetooth.lib.PenProfile.PROFILE_DELETE_VALUE;
import static kr.neolab.sdk.pen.bluetooth.lib.PenProfile.PROFILE_INFO;
import static kr.neolab.sdk.pen.bluetooth.lib.PenProfile.PROFILE_READ_VALUE;
import static kr.neolab.sdk.pen.bluetooth.lib.PenProfile.PROFILE_STATUS_SUCCESS;
import static kr.neolab.sdk.pen.bluetooth.lib.PenProfile.PROFILE_WRITE_VALUE;

/**
 * BT Connection in/out packet process
 *
 * @author Moo
 */
public class CommProcessor20 extends CommandManager implements IParsedPacketListener, IFilterListener
{
	/**
	 * Pen Up Flag
	 */
	private boolean isPrevDotDown = false;

	private boolean isStartWithDown = false;

	/**
	 * Previous packet (for storing pen-up dots)
	 */
	private Packet prevPacket;

	private IConnectedThread btConnection;

	private ProtocolParser20 parser;

	private int noteId = 0, pageId = 0;
	private int ownerId = 0, sectionId = 0;

	private int prevNoteId = 0, prevPageId = 0;
	private int prevOwnerId = 0, prevSectionId = 0;


	private long prevDotTime = 0;

	private int currColor = 0;
	private int currPenTipType = 0;

	private boolean isPenAuthenticated = false;

	private boolean requestOnDefaultCalibration = false;

	private Chunk chunk = null;

	/**
	 * The Is upgrading.
	 */
	public boolean isUpgrading = false;

	/**
	 * The Is upgrading suspended.
	 */
	public boolean isUpgradingSuspended = false;

	/**
	 * The R queue.
	 */
	public Queue<FwPacketInfo> rQueue = new LinkedList<FwPacketInfo>();


	private int packetId;

	private FilterForPaper dotFilterPaper = null;
	private FilterForFilm dotFilterFilm = null;

	private int oTotalDataSize = 0, oRcvDataSize = 0;
	private String appVer = "";
	private String receiveProtocolVer = "";
	private String FW_VER = "";

	private String connectedDeviceName = "";
	private String connectedSubName = "";
	private int fwPacketRetryCount = 0;

	private ArrayList<Stroke> offlineStrokes = new ArrayList<Stroke>();

	private Handler mHandler = new Handler( Looper.getMainLooper());

	private static final int OFFLINE_SEND_FAIL_TIME = 1000*20;

	private static final int PENINFO_SEND_FAIL_TIME = 1000 * 5;
	private static final int PENINFO_SEND_FAIL_TRY_COUNT = 3;

	private int penInfoReceiveCount = 0;
	private int penInfoRequestCount = 0;

	private String newPassword = "";
	private boolean reChkPassword = false;

	private String currentPassword = "";

	private int sensorType = 0;

	private int maxPress = 0;

	private float[] factor = null;

	/**
	 * The constant PEN_PROFILE_SUPPORT_PROTOCOL_VERSION.
	 */
	public static final float PEN_PROFILE_SUPPORT_PROTOCOL_VERSION = 2.10f;


	private class ChkOfflineFailRunnable implements Runnable{

		@Override
		public void run() {
			// fail process
			NLog.d( "[CommProcessor20] ChkOfflineFailRunnable Fail!!" );

			if ( offlineStrokes.size() != 0 )
			{
				Stroke[] strokes = offlineStrokes.toArray( new Stroke[offlineStrokes.size()] );

				OfflineByteData offlineByteData = new OfflineByteData( strokes, strokes[0].sectionId, strokes[0].ownerId, strokes[0].noteId );
				btConnection.onCreateOfflineStrokes( offlineByteData );
				offlineStrokes.clear();
			}
			btConnection.onCreateMsg( new PenMsg( PenMsgType.OFFLINE_DATA_SEND_FAILURE ) );
		}
	}

	private class ChkPenInfoFailRunnable implements Runnable{

		@Override
		public void run() {
			// fail process
			NLog.d( "[CommProcessor20] ChkPenInfoFailRunnable Fail!!" );
			reqPenInfo( );
		}
	}

	private ChkPenInfoFailRunnable mChkPenInfoFailRunnable;

	private ChkOfflineFailRunnable mChkOfflineFailRunnable;

	/**
	 * Instantiates a new Comm processor 20.
	 *
	 * @param conn    the conn
	 * @param version the version
	 */
	public CommProcessor20 (IConnectedThread conn , String version)
	{
		this.appVer = version;
		this.btConnection = conn;
		this.parser = new ProtocolParser20( this );
		this.dotFilterPaper = new FilterForPaper( this );
		this.dotFilterFilm = new FilterForFilm( this );
		this.mChkOfflineFailRunnable = new ChkOfflineFailRunnable();

		this.mChkPenInfoFailRunnable = new ChkPenInfoFailRunnable();

		// Since protocol 2.0, the app does not wait for a response from the pen but requests the pen information.
//		this.checkEstablish();
	}

	/**
	 * Finish.
	 */
	public void finish()
	{
		NLog.d( "finishOfflineData offlineStrokes.size()="+offlineStrokes.size() );
		if ( offlineStrokes.size() != 0 )
		{

			Stroke[] strokes = offlineStrokes.toArray( new Stroke[offlineStrokes.size()] );

			OfflineByteData offlineByteData = new OfflineByteData( strokes, strokes[0].sectionId, strokes[0].ownerId, strokes[0].noteId );
			btConnection.onCreateOfflineStrokes( offlineByteData );
			offlineStrokes.clear();
		}

		mHandler.removeCallbacks( mChkOfflineFailRunnable );
		mHandler.removeCallbacks( mChkPenInfoFailRunnable );
		penInfoReceiveCount = 0;
	}
	private void reqCalibrate2 ( float[] factor )
	{
		this.factor = factor;
	}

	public IConnectedThread getConn()
	{
		return this.btConnection;
	}

	/**
	 * save buffer in connected channel
	 * 
	 * @param data
	 * @param size
	 */
	public void fill( byte data[], int size )
	{
		parser.parseByteData( data, size );
	}

	/**
	 * record buffer with connected channel.
	 * 
	 * @param buffer
	 */
	public void write( byte[] buffer )
	{
		btConnection.write( buffer );
	}

	/**
	 * Incoming packet analysis
	 * 
	 * @param pack
	 */
	private void parsePacket( Packet pack )
	{

		int resultCode;
		NLog.d( "[CommProcessor20] parsePacket= "+pack. getCmd());
		/**
		 * Packet processing other than dot
		 */
		switch ( pack. getCmd() )
		{
			 /*
			 * ------------------------------------------------------------------
			 *
			 * RES_PenInfo (0x81)
			 *
			 * ------------------------------------------------------------------
			 */
			case CMD20.RES_PenInfo:
//				penInfoReceiveCount++;

				resultCode = pack.getResultCode();
				NLog.d( "[CommProcessor20] received RES_PenInfo(0x81) command. resultCode="+resultCode +"penInfoCount="+penInfoReceiveCount);

                penInfoReceiveCount = 0;
				mHandler.removeCallbacks( mChkPenInfoFailRunnable );
//				if(penInfoReceiveCount > 1)
//				{
//					return;
//				}
				if ( resultCode == 0x00)
				{
					NLog.d( "[CommProcessor20] connection is establised." );
					// Get the Pen Firmware Version
					connectedDeviceName = pack.getDataRangeString( 0, 16 ).trim();
					FW_VER = pack.getDataRangeString( 16, 16 ).trim();
					receiveProtocolVer = pack.getDataRangeString( 32, 8 ).trim();
					connectedSubName = pack.getDataRangeString( 40, 16 ).trim();


					sensorType = 0;
					try
					{
						sensorType = pack.getDataRangeInt( 64, 1 );
					}catch ( Exception e )
					{
						e.printStackTrace();
					}

					NLog.d( "[CommProcessor20] version of connected pen is " + FW_VER +";deviceName is"+connectedDeviceName+";subName is "+connectedSubName+";receiveProtocolVer is "+receiveProtocolVer+",sensorType="+sensorType);

					try
					{
						JSONObject job = new JSONObject()
								.put( JsonTag.STRING_PEN_FW_VERSION, FW_VER );

						job.put(  JsonTag.STRING_PROTOCOL_VERSION, "2");
						job.put(  JsonTag.STRING_DEVICE_NAME, connectedDeviceName);
						job.put(  JsonTag.STRING_SUB_NAME, connectedSubName);
						job.put(  JsonTag.INT_PRESS_SENSOR_TYPE, sensorType);

						btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_FW_VERSION, job ) );
					}
					catch ( JSONException e )
					{
						e.printStackTrace();
					}

					btConnection.onEstablished();
					btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_CONNECTION_SUCCESS ) );
					this.reqSetCurrentTime();

					write( ProtocolParser20.buildPenStatusData());

				}
				else
				{
					NLog.d( "[CommProcessor20] RES_PenInfo received error. pen will be shutdown." );
					btConnection.unbind(true);
				}
				break;

			/*
			 * ------------------------------------------------------------------
			 *
			 * RES_PenStatus (0x84)
			 *
			 * ------------------------------------------------------------------
			 */
			case CMD20.RES_PenStatus:
				resultCode = pack.getResultCode();
				NLog.d( "[CommProcessor20] received RES_PenStatus(0x84) command. resultCode="+resultCode );
				if ( resultCode == 0x00)
				{
					boolean isLock = pack.getDataRangeInt( 0, 1 ) == 0 ? false : true;
					int countReset = pack.getDataRangeInt( 1, 1 );
					int countRetry = pack.getDataRangeInt( 2, 1 );

					long stat_timestamp = pack.getDataRangeLong( 3, 8 );
//					stat_timestamp = TimeUtil.convertUTCToLocalTime( stat_timestamp );
					int stat_autopower_off_time = pack.getDataRangeInt( 11, 2 );
					maxPress = pack.getDataRangeInt( 13, 2 );
					int stat_usedmem = pack.getDataRangeInt( 15, 1 );
					boolean stat_pencap_off = pack.getDataRangeInt( 16, 1 ) == 0 ? false : true;
					boolean stat_autopower = pack.getDataRangeInt( 17, 1 ) == 0 ? false : true;
					boolean stat_beep = pack.getDataRangeInt( 18, 1 ) == 0 ? false : true;
					boolean stat_hovermode = pack.getDataRangeInt( 19, 1 ) == 0 ? false : true;
					int stat_battery = pack.getDataRangeInt( 20, 1 );

					boolean stat_offlinedata_save = pack.getDataRangeInt( 21, 1 ) == 0 ? false : true;
					NLog.d( "[CommProcessor20] received RES_PenStatus(0x84) command. stat_battery="+stat_battery +",isLock="+isLock+",stat_offlinedata_save="+stat_offlinedata_save);
					int stat_sensitivity = pack.getDataRangeInt( 22, 1 );


					try
					{
						JSONObject job = new JSONObject().put( JsonTag.STRING_PROTOCOL_VERSION, "2" )
								.put( JsonTag.LONG_TIMETICK, stat_timestamp )
								.put( JsonTag.INT_MAX_FORCE, maxPress )
								.put( JsonTag.INT_BATTERY_STATUS, stat_battery )
								.put( JsonTag.INT_MEMORY_STATUS, stat_usedmem )
								.put( JsonTag.BOOL_PENCAP_OFF, stat_pencap_off )
								.put( JsonTag.BOOL_AUTO_POWER_ON, stat_autopower )
								.put( JsonTag.BOOL_HOVER, stat_hovermode ).put( JsonTag.BOOL_BEEP, stat_beep )
								.put( JsonTag.INT_AUTO_POWER_OFF_TIME, stat_autopower_off_time )
								.put( JsonTag.BOOL_OFFLINEDATA_SAVE, stat_offlinedata_save )
								.put( JsonTag.INT_PEN_SENSITIVITY, stat_sensitivity );
						btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_STATUS, job ) );
					}
					catch ( JSONException e )
					{
						e.printStackTrace();
					}
					if(!isPenAuthenticated)
					{
						if(isLock)
						{
							JSONObject job = null;

							try
							{
								job = new JSONObject();
								job.put( JsonTag.INT_PASSWORD_RETRY_COUNT, countRetry );
								job.put( JsonTag.INT_PASSWORD_RESET_COUNT, countReset );

								btConnection.onCreateMsg( new PenMsg( PenMsgType.PASSWORD_REQUEST, job ) );
							}
							catch ( JSONException e )
							{
								e.printStackTrace();
							}
						}
						else
						{
							if ( !isPenAuthenticated )
							{
								isPenAuthenticated = true;
								btConnection.onAuthorized();
								if(isSupportPenProfile())
								{
									requestOnDefaultCalibration = true;
									requestDefaultCalibration();
								}
								else
								{
									try
									{

										JSONObject job = new JSONObject()
												.put( JsonTag.STRING_PEN_MAC_ADDRESS, btConnection.getMacAddress() )
												.put( JsonTag.STRING_PEN_PASSWORD, currentPassword );
										btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_AUTHORIZED, job ) );
									}
									catch ( JSONException e )
									{
										e.printStackTrace();
									}

								}
							}
						}

					}
				}
				else
				{
					if(!isPenAuthenticated)
					{
						NLog.d( "[CommProcessor20] RES_PenStatus received error. pen will be shutdown." );
						btConnection.unbind();
					}
				}
				break;

			/*
			 * ------------------------------------------------------------------
			 *
			 * RES_Password (0x82)
			 *
			 * ------------------------------------------------------------------
			 */
			case CMD20.RES_Password:
			{
				resultCode = pack.getResultCode();
				NLog.d( "[CommProcessor20] received RES_Password(0x82) command. resultCode="+resultCode );
				if ( resultCode == 0x00)
				{
					int status = pack.getDataRangeInt( 0, 1 );
					int countRetry = pack.getDataRangeInt( 1, 1 );
					int countReset = pack.getDataRangeInt( 2, 1 );


					if(status == 1)
					{
						if(reChkPassword)
						{
							currentPassword = newPassword;
							btConnection.onCreateMsg( new PenMsg( PenMsgType.PASSWORD_SETUP_SUCCESS ) );
							reChkPassword = false;
							return;
						}

						if ( !isPenAuthenticated )
						{
							isPenAuthenticated = true;
							btConnection.onAuthorized();

							if(isSupportPenProfile())
							{
								requestOnDefaultCalibration = true;
								requestDefaultCalibration();
							}
							else
							{
								try
								{

									JSONObject job = new JSONObject()
											.put( JsonTag.STRING_PEN_MAC_ADDRESS, btConnection.getMacAddress() )
											.put( JsonTag.STRING_PEN_PASSWORD, currentPassword );
									btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_AUTHORIZED, job ) );
								}
								catch ( JSONException e )
								{
									e.printStackTrace();
								}

							}

						}
					}
					else
					{
						NLog.d( "[CommProcessor20] RES_Password ( " + countRetry + " / " + countReset + " )" );
						JSONObject job = null;

						try
						{
							job = new JSONObject();
							job.put( JsonTag.INT_PASSWORD_RETRY_COUNT, countRetry );
							job.put( JsonTag.INT_PASSWORD_RESET_COUNT, countReset );
							if(reChkPassword)
							{
								reChkPassword = false;
								btConnection.onCreateMsg( new PenMsg( PenMsgType.PASSWORD_SETUP_FAILURE, job ) );
							}
							else
							{
								btConnection.onCreateMsg( new PenMsg( PenMsgType.PASSWORD_REQUEST, job ) );
							}
						}
						catch ( JSONException e )
						{
							e.printStackTrace();
						}

					}
				}
				else
				{
					NLog.d( "[CommProcessor20] RES_Password received error. pen will be shutdown." );
					btConnection.unbind();

				}

			}
			break;

			/*
			 * ------------------------------------------------------------------
			 *
			 * RES_PasswordSet (0x83)
			 *
			 * ------------------------------------------------------------------
			 */
			case CMD20.RES_PasswordSet:
			{
				int result = pack.getResultCode();

				NLog.d( "[CommProcessor20] A_PasswordSetResponse => " + result );
				int countRetry = pack.getDataRangeInt( 0, 1 );
				int countReset = pack.getDataRangeInt( 1, 1 );

				if ( result == 0x00 )
				{
					reChkPassword = true;
					write( ProtocolParser20.buildPasswordInput( this.newPassword ));
				}
				else
				{
					this.newPassword = "";
					try
					{
						JSONObject job = null;
						job = new JSONObject();
						job.put( JsonTag.INT_PASSWORD_RETRY_COUNT, countRetry );
						job.put( JsonTag.INT_PASSWORD_RESET_COUNT, countReset );

						btConnection.onCreateMsg( new PenMsg( PenMsgType.PASSWORD_SETUP_FAILURE, job ) );
					}
					catch ( JSONException e )
					{
						e.printStackTrace();
					}
				}
			}
			break;

			/*
			 * ------------------------------------------------------------------
			 *
			 * RES_UsingNoteNotify (0x11)
			 *
			 * ------------------------------------------------------------------
			 */
			case CMD20.RES_UsingNoteNotify:
			{
				int result = pack.getResultCode();
				NLog.d( "[CommProcessor20] RES_UsingNoteNotify :  resultCode =" + pack.getResultCode() );
				boolean isSuccess = result == 1 ? true : false;
				try
				{
					JSONObject job = new JSONObject().put( JsonTag.BOOL_RESULT, isSuccess );
					btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_USING_NOTE_SET_RESULT, job ) );
				}
				catch ( Exception e )
				{
					e.printStackTrace();
				}
			}
				break;

			/*
			 * ----------------------------------------------------------------
			 *
			 * RES_EventPenUpDown (0x63)
			 *
			 * ----------------------------------------------------------------
			 */
			case CMD20.RES_EventPenUpDown:

				// NLog.d("[CommProcessor20] A_DotUpDownData");
				int PEN_UP_DOWN = pack.getDataRangeInt( 0, 1 );
				long MTIME = 0;
				try
				{
					MTIME = pack.getDataRangeLong( 1, 8 );
//					MTIME = TimeUtil.convertUTCToLocalTime( MTIME );
					SimpleDateFormat dayTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
					String str = dayTime.format( new Date( MTIME  ) );
					NLog.d( "[CommProcessor20] received RES_EventPenUpDown() command. PEN_UP_DOWN = "+PEN_UP_DOWN+",str=" + str );

				}
				catch ( Exception e )
				{
					e.printStackTrace();
				}
				currPenTipType = pack.getDataRangeInt( 9, 1 );
				if ( PEN_UP_DOWN == 0 )
				{
					// In case of pen down, set the timestamp of the Start Dot
					this.prevDotTime = MTIME;
					this.isPrevDotDown = true;
					this.isStartWithDown = true;
				}
				else if ( PEN_UP_DOWN == 1 )
				{
					if ( prevPacket != null )
					{
						// In case of pen-up, Insert previous dots as End Dots
						int pFORCE = 0;
						int pX = 0;
						int pY = 0;
						int pFX = 0;
						int pFY = 0;
						int TILT_X = 0;
						int TILT_Y = 0;
						int twist = 0;

						if(prevPacket.getDotCode() == 1)
						{
							pFORCE = prevPacket.getDataRangeInt( 1, 2 );
							pX = prevPacket.getDataRangeInt( 3, 2 );
							pY = prevPacket.getDataRangeInt( 5, 2 );
							pFX = prevPacket.getDataRangeInt( 7, 1 );
							pFY = prevPacket.getDataRangeInt( 8, 1 );
							TILT_X = prevPacket.getDataRangeInt( 9, 1 );
							TILT_Y = prevPacket.getDataRangeInt( 10, 1 );
							twist = prevPacket.getDataRangeInt( 11, 2 );
						}
						else if(prevPacket.getDotCode() == 2)
						{
							pFORCE = 1020;
							pX = prevPacket.getDataRangeInt( 1, 2 );
							pY = prevPacket.getDataRangeInt( 3, 2 );
							pFX = prevPacket.getDataRangeInt( 5, 1 );
							pFY = prevPacket.getDataRangeInt( 6, 1 );
							TILT_X = 0;
							TILT_Y = 0;
							twist = 0;
						}
						else if(prevPacket.getDotCode() == 3)
						{
							pFORCE = 1020;
							pX = prevPacket.getDataRangeInt( 1, 1 );
							pY = prevPacket.getDataRangeInt( 2, 1 );
							pFX = prevPacket.getDataRangeInt( 3, 1 );
							pFY = prevPacket.getDataRangeInt( 4, 1 );
							TILT_X = 0;
							TILT_Y = 0;
							twist = 0;
						}
						long pTimeLong = MTIME;

						this.processDot( sectionId, ownerId, noteId, pageId, pTimeLong, pX, pY, pFX, pFY, pFORCE, DotType.PEN_ACTION_UP.getValue(), currColor ,TILT_X, TILT_Y,  twist, currPenTipType);
					}
//					else
//					{
//						this.processDot( sectionId, ownerId, noteId, pageId, 0, 0, 0, 0, 0, 0, DotType.PEN_ACTION_DOWN.getValue(), currColor ,0, 0,  0, currPenTipType);
//						this.processDot( sectionId, ownerId, noteId, pageId, 0, 0, 0, 0, 0, 0, DotType.PEN_ACTION_UP.getValue(), currColor ,0, 0,  0, currPenTipType);
//					}
					this.isStartWithDown = false;
				}

				this.prevPacket = null;

				byte[] cbyte = pack.getDataRange( 10, 4 );
				NLog.d( "a : " + Integer.toHexString( (int) ( cbyte[3] & 0xFF ) ) );
				NLog.d( "r : " + Integer.toHexString( (int) ( cbyte[2] & 0xFF ) ) );
				NLog.d( "g : " + Integer.toHexString( (int) ( cbyte[1] & 0xFF ) ) );
				NLog.d( "b : " + Integer.toHexString( (int) ( cbyte[0] & 0xFF ) ) );

				currColor = ByteConverter.byteArrayToInt( new byte[]{ cbyte[0], cbyte[1], cbyte[2], cbyte[3] } );

				// NLog.d("[CommProcessor20] new dot noteId = " + noteId + " / pageId = " + pageId + " / timeLong : " + NumberFormat.getInstance().format(MTIME) + " / state : " + PEN_UP_DOWN);
				break;


			/*
			 * ------------------------------------------------------------------
			 *
			 * RES_EventIdChange (0x64)
			 *
			 * ------------------------------------------------------------------
			 */
			case CMD20.RES_EventIdChange:

				// Change note page information
				byte[] osbyte = pack.getDataRange( 0, 4 );

				prevSectionId = sectionId;
				prevOwnerId = ownerId;
				prevNoteId = noteId;
				prevPageId = pageId;

				sectionId = (int) (osbyte[3] & 0xFF);
				ownerId = ByteConverter.byteArrayToInt( new byte[] { osbyte[0], osbyte[1], osbyte[2], (byte) 0x00 } );

				noteId = pack.getDataRangeInt( 4, 4 );
				pageId = pack.getDataRangeInt( 8, 4 );
				NLog.d( "[CommProcessor20] received RES_EventIdChange() sectionId = "+sectionId+",ownerId="+ownerId+",noteId=" + noteId );

				if ( prevPacket != null )
				{
					// If the page is changed but the Pen Up signal is not turned on, it handles the pen-down process simultaneously with the pen-up.
					int pFORCE = 0;
					int pX = 0;
					int pY = 0;
					int pFX = 0;
					int pFY = 0;
					int TILT_X = 0;
					int TILT_Y = 0;
					int twist = 0;

					if(prevPacket.getDotCode() == 1)
					{
						pFORCE = prevPacket.getDataRangeInt( 1, 2 );
						pX = prevPacket.getDataRangeInt( 3, 2 );
						pY = prevPacket.getDataRangeInt( 5, 2 );
						pFX = prevPacket.getDataRangeInt( 7, 1 );
						pFY = prevPacket.getDataRangeInt( 8, 1 );
						TILT_X = prevPacket.getDataRangeInt( 9, 1 );
						TILT_Y = prevPacket.getDataRangeInt( 10, 1 );
						twist = prevPacket.getDataRangeInt( 11, 2 );
					}
					else if(prevPacket.getDotCode() == 2)
					{
						pFORCE = 1020;
						pX = prevPacket.getDataRangeInt( 1, 2 );
						pY = prevPacket.getDataRangeInt( 3, 2 );
						pFX = prevPacket.getDataRangeInt( 5, 1 );
						pFY = prevPacket.getDataRangeInt( 6, 1 );
						TILT_X = 0;
						TILT_Y = 0;
						twist = 0;
					}
					else if(prevPacket.getDotCode() == 3)
					{
						pFORCE = 1020;
						pX = prevPacket.getDataRangeInt( 1, 1 );
						pY = prevPacket.getDataRangeInt( 2, 1 );
						pFX = prevPacket.getDataRangeInt( 3, 1 );
						pFY = prevPacket.getDataRangeInt( 4, 1 );
						TILT_X = 0;
						TILT_Y = 0;
						twist = 0;
					}
					long pTimeLong = prevDotTime;

					this.processDot( prevSectionId, prevOwnerId, prevNoteId, prevPageId, pTimeLong, pX, pY, pFX, pFY, pFORCE, DotType.PEN_ACTION_UP.getValue(), currColor ,TILT_X, TILT_Y,  twist, currPenTipType);
					isPrevDotDown = true;
					isStartWithDown = true;
				}


				break;

			/*
			 * --------------------------------------------------------------------
			 *
			 * RES_EventDotData (0x65)
			 *
			 * --------------------------------------------------------------------
			 */
			case CMD20.RES_EventDotData:
			{
				long TIME = pack.getDataRangeInt(0, 1);
				long timeLong = prevDotTime + TIME;
				int FORCE = pack.getDataRangeInt(1, 2);
				int X = pack.getDataRangeInt(3, 2);
				int Y = pack.getDataRangeInt(5, 2);
				int FLOAT_X = pack.getDataRangeInt(7, 1);
				int FLOAT_Y = pack.getDataRangeInt(8, 1);
				int TILT_X = pack.getDataRangeInt(9, 1);
				int TILT_Y = pack.getDataRangeInt(10, 1);
				int twist = pack.getDataRangeInt(11, 2);

				// NLog.d("[CommProcessor20] new dot noteId = " + noteId +
				// " / pageId = " + pageId + " / timeLong : " + timeLong +
				// " / timeAdd : " + TIME + " / x : " + X + " / y : " + Y +
				// " / fx : " + FLOAT_X + " / fy : " + FLOAT_Y);

				NLog.d( "[CommProcessor20] received RES_EventDotData1() sectionId = "+sectionId+",ownerId="+ownerId+",noteId=" + noteId+",X="+X+",Y="+Y+",FLOAT_X="+FLOAT_X+",FLOAT_Y"+FLOAT_Y );
				if ( !isStartWithDown || timeLong < 10000 )
				{
					NLog.e( "[CommProcessor20] this stroke start with middle dot." );
					return;
				}

				if (isPrevDotDown) {
					// In case of pen-up, save as start dot
					this.isPrevDotDown = false;
					this.processDot(sectionId, ownerId, noteId, pageId, timeLong, X, Y, FLOAT_X, FLOAT_Y, FORCE, DotType.PEN_ACTION_DOWN.getValue(), currColor, TILT_X, TILT_Y, twist, currPenTipType);
				} else {
					// If it is not a pen-up, save it as a middle dot.
					this.processDot(sectionId, ownerId, noteId, pageId, timeLong, X, Y, FLOAT_X, FLOAT_Y, FORCE, DotType.PEN_ACTION_MOVE.getValue(), currColor, TILT_X, TILT_Y, twist, currPenTipType);
				}

				prevDotTime = timeLong;
				pack.setDotCode( 1 );
				prevPacket = pack;
			}
				break;
						/*
			 * --------------------------------------------------------------------
			 *
			 * RES_EventDotData2 (0x66)
			 *
			 * --------------------------------------------------------------------
			 */
			case CMD20.RES_EventDotData2:
			{
				long TIME = pack.getDataRangeInt( 0, 1 );
				long timeLong = prevDotTime + TIME;
				// max value
				int FORCE = 1020;
				int X = pack.getDataRangeInt( 1, 2 );
				int Y = pack.getDataRangeInt( 3, 2 );
				int FLOAT_X = pack.getDataRangeInt( 5, 1 );
				int FLOAT_Y = pack.getDataRangeInt( 6, 1 );
				// default values
				int TILT_X = 0;
				int TILT_Y = 0;
				int twist = 0;

				if ( !isStartWithDown || timeLong < 10000 )
				{
					NLog.e( "[CommProcessor20] this stroke start with middle dot." );
					return;
				}
				NLog.d( "[CommProcessor20] received RES_EventDotData2() sectionId = "+sectionId+",ownerId="+ownerId+",noteId=" + noteId+",X="+X+",Y="+Y+",FLOAT_X="+FLOAT_X+",FLOAT_Y"+FLOAT_Y );

				if ( isPrevDotDown )
				{
					// In case of pen-up, save as start dot
					this.isPrevDotDown = false;
					this.processDot( sectionId, ownerId, noteId, pageId, timeLong, X, Y, FLOAT_X, FLOAT_Y, FORCE, DotType.PEN_ACTION_DOWN.getValue(), currColor, TILT_X, TILT_Y, twist, currPenTipType );
				}
				else
				{
					// If it is not a pen-up, save it as a middle dot.
					this.processDot( sectionId, ownerId, noteId, pageId, timeLong, X, Y, FLOAT_X, FLOAT_Y, FORCE, DotType.PEN_ACTION_MOVE.getValue(), currColor, TILT_X, TILT_Y, twist, currPenTipType );
				}

				prevDotTime = timeLong;
				pack.setDotCode( 2 );
				prevPacket = pack;
			}
			break;

			/*
			 * --------------------------------------------------------------------
			 *
			 * RES_EventDotData3 (0x67)
			 *
			 * --------------------------------------------------------------------
			 */
			case CMD20.RES_EventDotData3:
			{
				long TIME = pack.getDataRangeInt( 0, 1 );
				long timeLong = prevDotTime + TIME;
				// max value
				int FORCE = 1020;
				int X = pack.getDataRangeInt( 1, 1 );
				int Y = pack.getDataRangeInt( 2, 1 );
				int FLOAT_X = pack.getDataRangeInt( 3, 1 );
				int FLOAT_Y = pack.getDataRangeInt( 4, 1 );
				// default value
				int TILT_X = 0;
				int TILT_Y = 0;
				int twist = 0;

				if ( !isStartWithDown || timeLong < 10000 )
				{
					NLog.e( "[CommProcessor20] this stroke start with middle dot." );
					return;
				}

				NLog.d( "[CommProcessor20] received RES_EventDotData3() sectionId = "+sectionId+",ownerId="+ownerId+",noteId=" + noteId+",X="+X+",Y="+Y+",FLOAT_X="+FLOAT_X+",FLOAT_Y"+FLOAT_Y );
				if ( isPrevDotDown )
				{
					// In case of pen-up, save as start dot
					this.isPrevDotDown = false;
					this.processDot( sectionId, ownerId, noteId, pageId, timeLong, X, Y, FLOAT_X, FLOAT_Y, FORCE, DotType.PEN_ACTION_DOWN.getValue(), currColor, TILT_X, TILT_Y, twist, currPenTipType );
				}
				else
				{
					// If it is not a pen-up, save it as a middle dot.
					this.processDot( sectionId, ownerId, noteId, pageId, timeLong, X, Y, FLOAT_X, FLOAT_Y, FORCE, DotType.PEN_ACTION_MOVE.getValue(), currColor, TILT_X, TILT_Y, twist, currPenTipType );
				}

				prevDotTime = timeLong;
				pack.setDotCode( 3 );
				prevPacket = pack;
			}
			break;

			/*
			 * ------------------------------------------------------------------
			 *
			 * RES_PenStatusChange (0x85)
			 *
			 * ------------------------------------------------------------------
			 */
			case CMD20.RES_PenStatusChange:

				resultCode = pack.getResultCode();
				int type = pack.getDataRangeInt( 0, 1 );
				NLog.d( "[CommProcessor20] received RES_PenStatusChange(0x04) command. resultCode=" + resultCode + " type=" + type );
				boolean isSuccess = resultCode == 0x00 ? true : false;
				JSONObject job = null;
				try
				{
					job = new JSONObject().put( JsonTag.BOOL_RESULT, isSuccess );
				}
				catch ( JSONException e )
				{
					e.printStackTrace();
				}

				switch ( type )
				{
					case CMD20.REQ_PenStatusChange_TYPE_CurrentTimeSet:
						if (isSuccess )
						{
							int k = (CMD20.REQ_PenStatusChange << 8) & 0xff00 + CMD20.REQ_PenStatusChange_TYPE_CurrentTimeSet ;
							kill( k );
						}
						break;
					case CMD20.REQ_PenStatusChange_TYPE_AutoShutdownTime:
						if(job != null)
							btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_SETUP_AUTO_SHUTDOWN_RESULT, job ) );
						break;

					case CMD20.REQ_PenStatusChange_TYPE_PenCapOnOff:
						if(job != null)
							btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_SETUP_PEN_CAP_OFF, job ) );
						break;
					case CMD20.REQ_PenStatusChange_TYPE_AutoPowerOnSet:
						if(job != null)
							btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_SETUP_AUTO_POWER_ON_RESULT, job ) );
						break;
					case CMD20.REQ_PenStatusChange_TYPE_BeepOnOff:
						if(job != null)
							btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_SETUP_BEEP_RESULT, job ) );
						break;
					case CMD20.REQ_PenStatusChange_TYPE_HoverOnOff:
						if(job != null)
							btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_SETUP_HOVER_ONOFF, job ) );
						break;
					case CMD20.REQ_PenStatusChange_TYPE_OfflineDataSaveOnOff:
						if(job != null)
							btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_SETUP_OFFLINEDATA_SAVE_ONOFF, job ) );
						break;
					case CMD20.REQ_PenStatusChange_TYPE_LEDColorSet:
						if(job != null)
							btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_SETUP_PEN_COLOR_RESULT, job ) );
						break;
					case CMD20.REQ_PenStatusChange_TYPE_SensitivitySet:
						if(resultCode == 3)
						{
							btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_SETUP_SENSITIVITY_NOT_SUPPORT_DEVICE) );
						}
						else
						{
							if(job != null)
								btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_SETUP_SENSITIVITY_RESULT, job ) );
						}
						break;

					case CMD20.REQ_PenStatusChange_TYPE_SensitivitySet_FSC:
						if(resultCode == 3)
						{
							btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_SETUP_SENSITIVITY_NOT_SUPPORT_DEVICE) );
						}
						else
						{
							if(job != null)
								btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_SETUP_SENSITIVITY_RESULT_FSC, job ) );
						}
						break;


				}
				break;

			/*
			 * ------------------------------------------------------------------
			 *
			 * RES_OfflineNoteList (0xA1)
			 *
			 * ------------------------------------------------------------------
			 */
			case CMD20.RES_OfflineNoteList:
				resultCode = pack.getResultCode();
				NLog.d( "[CommProcessor20] received RES_OfflineNoteList(0xA1) command. resultCode=" + resultCode );
				if ( resultCode == 0x00 )
				{

					int setCnt = pack.getDataRangeInt( 0, 2 );

					try
					{
						for ( int i = 0; i < setCnt; i++ )
						{
							byte[] rxb = pack.getDataRange( 2 + i*8, 4 );
							int oSectionId = (int) (rxb[3] & 0xFF);
							int oOwnerId = ByteConverter.byteArrayToInt( new byte[]{ rxb[0], rxb[1], rxb[2], (byte) 0x00 } );
							int noteId = pack.getDataRangeInt( 2 + i*8 + 4, 4 );
							NLog.d( "[CommProcessor20] RES_OfflineNoteList => sectionId : " + oSectionId + ", ownerId : " + oOwnerId + ", noteId : " + noteId );

							offlineNoteInfos.put( new JSONObject().put( JsonTag.INT_OWNER_ID, oOwnerId ).put( JsonTag.INT_SECTION_ID, oSectionId ).put( JsonTag.INT_NOTE_ID, noteId ) );
						}
						btConnection.onCreateMsg( new PenMsg( PenMsgType.OFFLINE_DATA_NOTE_LIST, offlineNoteInfos ) );
						offlineNoteInfos = new JSONArray();
					}
					catch ( JSONException e )
					{
						e.printStackTrace();
					}

				}

				break;

			/*
			 * ------------------------------------------------------------------
			 *
			 * RES_OfflineNoteList (0xA2)
			 *
			 * ------------------------------------------------------------------
			 */
			case CMD20.RES_OfflinePageList:
				resultCode = pack.getResultCode();
				NLog.d( "[CommProcessor20] received RES_OfflineNoteList(0xA2) command. resultCode=" + resultCode );
				if ( resultCode == 0x00 )
				{
					offlinePageInfos =  new JSONArray(  );
					byte[] rxb = pack.getDataRange( 0 , 4 );
					int oSectionId = (int) (rxb[3] & 0xFF);
					int oOwnerId = ByteConverter.byteArrayToInt( new byte[]{ rxb[0], rxb[1], rxb[2], (byte) 0x00 } );
					int noteId = pack.getDataRangeInt( 4, 4 );
					int setCnt = pack.getDataRangeInt( 8, 2 );

					try
					{
						for ( int i = 0; i < setCnt; i++ )
						{
							int pageId = pack.getDataRangeInt( 10 + i * 4 , 4 );
							NLog.d( "[CommProcessor20] RES_OfflineNoteList => sectionId : " + oSectionId + ", ownerId : " + oOwnerId + ", noteId : " + noteId );
							offlinePageInfos.put( new JSONObject().put( JsonTag.INT_OWNER_ID, oOwnerId ).put( JsonTag.INT_SECTION_ID, oSectionId ).put( JsonTag.INT_NOTE_ID, noteId ).put( JsonTag.INT_PAGE_ID, pageId ) );
						}
						btConnection.onCreateMsg( new PenMsg( PenMsgType.OFFLINE_DATA_PAGE_LIST, offlinePageInfos ) );
					}
					catch ( JSONException e )
					{
						e.printStackTrace();
					}
				}
				break;

			/*
			 * ------------------------------------------------------------------
			 *
			 * RES_OfflineDataRequest (0xA3)
			 *
			 * ------------------------------------------------------------------
			 */
			case CMD20.RES_OfflineDataRequest:
				resultCode = pack.getResultCode();
				NLog.d( "[CommProcessor20] received RES_OfflineDataRequest(0xA3) command. resultCode=" + resultCode );
				if ( resultCode == 0x00 )
				{
					if ( !btConnection.getAllowOffline())
					{
						return;
					}
					int strokeCount = pack.getDataRangeInt( 0, 4 );
					oTotalDataSize= pack.getDataRangeInt( 4, 4 );
					oRcvDataSize = 0;
					boolean isCompress = pack.getDataRangeInt( 8, 1 ) == 1 ? true: false;

					NLog.i( "[CommProcessor20] offline file transfer is started ( oTotalDataSize : " + oTotalDataSize + ", strokeCount:"+strokeCount+" isCompress:" + isCompress + " )" );
					if(oTotalDataSize > 0)
						btConnection.onCreateMsg( new PenMsg( PenMsgType.OFFLINE_DATA_SEND_START ) );
					else
						btConnection.onCreateMsg( new PenMsg( PenMsgType.OFFLINE_DATA_SEND_FAILURE ) );

				}
				else
				{
					btConnection.onCreateMsg( new PenMsg( PenMsgType.OFFLINE_DATA_SEND_FAILURE ) );
				}

				break;

			/*
			 * ------------------------------------------------------------------
			 *
			 * RES_OfflineChunk (0x24)
			 *
			 * ------------------------------------------------------------------
			 */
			case CMD20.RES_OfflineChunk:
			{
				resultCode = pack.getResultCode();
				NLog.d( "[CommProcessor20] received RES_OfflineChunk(0x24) command. resultCode=" + resultCode );
				if ( resultCode == 0x00 )
				{
					packetId = pack.getDataRangeInt( 0, 2 );
					mHandler.removeCallbacks( mChkOfflineFailRunnable );
					// 0 : start 1: middle 2: end
					int position = pack.getDataRangeInt( 7, 1 );
					NLog.d( "[CommProcessor20] received RES_OfflineChunk(0x24) command. packetId=" + packetId + ";position=" + position);
					int sizeBeforeCompress = pack.getDataRangeInt( 3, 2 );
					OfflineByteParser parser = new OfflineByteParser( pack.getData() , maxPress);
					if(factor != null)
						parser.setCalibrate( factor );
					try
					{
						offlineStrokes.addAll( parser.parse() );
					}
					catch ( Exception e )
					{
						NLog.e( "[CommProcessor20] deCompress parse Error !!!" );
						e.printStackTrace();
						write( ProtocolParser20.buildOfflineChunkResponse( 1, packetId, position ) );
						mHandler.postDelayed( mChkOfflineFailRunnable, OFFLINE_SEND_FAIL_TIME );
						return;
					}

					oRcvDataSize += sizeBeforeCompress;
					// If you have received the chunk, process the offline data.
					if ( position == 2 )
					{
						if ( offlineStrokes.size() != 0 )
						{
							Stroke[] strokes = offlineStrokes.toArray( new Stroke[offlineStrokes.size()] );

							OfflineByteData offlineByteData = new OfflineByteData( strokes, strokes[0].sectionId, strokes[0].ownerId, strokes[0].noteId );
							btConnection.onCreateOfflineStrokes( offlineByteData );
							offlineStrokes.clear();
						}
					}
					write( ProtocolParser20.buildOfflineChunkResponse( 0, packetId, position ) );
					if ( position != 2 ) mHandler.postDelayed( mChkOfflineFailRunnable, OFFLINE_SEND_FAIL_TIME );

					try
					{
						JSONObject jsonObject = new JSONObject();
						jsonObject.put( JsonTag.INT_TOTAL_SIZE, this.oTotalDataSize );
						jsonObject.put( JsonTag.INT_RECEIVED_SIZE, this.oRcvDataSize );

						btConnection.onCreateMsg( new PenMsg( PenMsgType.OFFLINE_DATA_SEND_STATUS, jsonObject ) );
					}
					catch ( JSONException e )
					{
						e.printStackTrace();
					}
				}
				else
				{
					btConnection.onCreateMsg( new PenMsg( PenMsgType.OFFLINE_DATA_SEND_FAILURE ) );
				}
			}
			break;

			/*
			 * ------------------------------------------------------------------
			 *
			 * RES_OfflineNoteRemove (0xA5)
			 *
			 * ------------------------------------------------------------------
			 */
			case CMD20.RES_OfflineNoteRemove:
				resultCode = pack.getResultCode();
				NLog.d( "[CommProcessor20] received RES_OfflineNoteRemove(0xA5) command. resultCode=" + resultCode );
				if ( resultCode == 0x00 )
				{
					byte[] rxb = pack.getDataRange( 0 , 4 );
					int oSectionId = (int) (rxb[3] & 0xFF);
					int oOwnerId = ByteConverter.byteArrayToInt( new byte[]{ rxb[0], rxb[1], rxb[2], (byte) 0x00 } );
					int noteCount = pack.getDataRangeInt( 4, 1 );
					int[] noteIds = new int[noteCount];
					String delete_msg = " delete noteid :";
					for(int i = 0; i < noteCount; i++)
					{
						noteIds[i] = pack.getDataRangeInt( 5 + 4 * i, 4 );
						delete_msg += noteIds[i]+",";
					}
					NLog.d( "[CommProcessor20] received RES_OfflineNoteRemove(0xA5) command. oSectionId=" + oSectionId + " oOwnerId="+oOwnerId+" noteCount="+noteCount+ delete_msg);
					btConnection.onCreateMsg( new PenMsg( PenMsgType.OFFLINE_DATA_FILE_DELETED ) );
				}
				break;
			

			/*
			 * ------------------------------------------------------------------
			 *
			 * RES_PenFWUpgrade (0xB1)
			 *
			 * ------------------------------------------------------------------
			 */
			case CMD20.RES_PenFWUpgrade:
				resultCode = pack.getResultCode();
				NLog.d( "[CommProcessor20] RES_PenFWUpgrade resultCode: " + resultCode );
				if ( resultCode == 0x00 )
				{
					int status = pack.getDataRangeInt( 0, 1 );
					NLog.d( "[CommProcessor20] RES_PenFWUpgrade status : " + status  );
					// pen installed equal fw version.
					if(status == 1)
					{
						NLog.e( "[CommProcessor20] RES_PenFWUpgrade pen installed equal fw version. " );
						finishUpgrade();
						btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_FW_UPGRADE_FAILURE ) );
					}
					else if(status == 2)
					{
						NLog.e( "[CommProcessor20] RES_PenFWUpgrade not enough pen memory " );
						finishUpgrade();
						btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_FW_UPGRADE_FAILURE ) );
					}
					else if(status == 3)
					{
						NLog.e( "[CommProcessor20] RES_PenFWUpgrade response fail " );
						finishUpgrade();
						btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_FW_UPGRADE_FAILURE ) );
					}
					else
					{
						if ( chunk == null )
						{
							NLog.e( "[CommProcessor20] pen upgrade job has not been initialized." );
							finishUpgrade();
							btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_FW_UPGRADE_FAILURE ) );
							return;
						}
					}
				}
				else
				{
					NLog.e( "[CommProcessor20] RES_PenFWUpgrade received error code" );
					finishUpgrade();
					btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_FW_UPGRADE_FAILURE ) );
				}
				break;


			/*
			 * ------------------------------------------------------------------
			 *
			 * RES_UploadPenSW (0x32)
			 *
			 * ------------------------------------------------------------------
			 */
			case CMD20.RES_EventUploadPenFWChunk:
				int status = pack.getDataRangeInt( 0, 1 );
				int offset = pack.getDataRangeInt( 1, 4 );
				NLog.d( "[CommProcessor20] RES_EventUploadPenFWChunk status: " + status +",offset="+offset);
				resPenSwUpgStatus( offset ,status);
				break;

			case CMD20.RES_EventBattery:
				int battery = pack.getDataRangeInt( 0, 1 );
				NLog.d( "[CommProcessor20] RES_EventBattery battery: " + battery);
				try
				{
					JSONObject jsonObject = new JSONObject();
					jsonObject.put( JsonTag.INT_BATTERY_STATUS, battery );

					btConnection.onCreateMsg( new PenMsg( PenMsgType.EVENT_LOW_BATTERY ,jsonObject) );
				}
				catch ( Exception e )
				{
					e.printStackTrace();
				}
				break;
			case CMD20.RES_EventPowerOff:
				int reason = pack.getDataRangeInt( 0, 1 );
				NLog.d( "[CommProcessor20] RES_EventPowerOff reason: " + reason);
				try
				{
					JSONObject jsonObject = new JSONObject();
					jsonObject.put( JsonTag.INT_POWER_OFF_REASON, reason );
					btConnection.onCreateMsg( new PenMsg( PenMsgType.EVENT_POWER_OFF,jsonObject ) );
					btConnection.unbind( true );
				}
				catch ( Exception e )
				{
					e.printStackTrace();
				}
				break;

			case CMD20.RES_PenProfile:
			{
				JSONArray jsonArray = null;
				resultCode = pack.getResultCode();
				NLog.d( "[CommProcessor20] RES_PenProfile resultCode: " + resultCode);
				String profile_name = pack.getDataRangeString( 0, 8 ).trim();
				int res_type = pack.getDataRangeInt( 8, 1 );

				if ( resultCode == 0x00 )
				{
					JSONObject jsonObject = new JSONObject();
					if ( res_type == PROFILE_CREATE )
					{
						int res_status = pack.getDataRangeInt( 9, 1 );
						try
						{
							jsonObject.put( JsonTag.STRING_PROFILE_NAME, profile_name );
							jsonObject.put( JsonTag.INT_PROFILE_RES_STATUS, res_status );
//							jsonObject.put( JsonTag.STRING_PROFILE_RES_MSG, res_status );
						}
						catch ( JSONException e )
						{
							e.printStackTrace();
						}
						btConnection.onCreateMsg( new PenMsg( PenMsgType.PROFILE_CREATE, jsonObject ) );
					}
					else if ( res_type == PROFILE_DELETE )
					{
						int res_status = pack.getDataRangeInt( 9, 1 );

						try
						{
							jsonObject.put( JsonTag.STRING_PROFILE_NAME, profile_name );
							jsonObject.put( JsonTag.INT_PROFILE_RES_STATUS, res_status );
						}
						catch ( JSONException e )
						{
							e.printStackTrace();
						}
						btConnection.onCreateMsg( new PenMsg( PenMsgType.PROFILE_DELETE, jsonObject ) );

					}
					else if ( res_type == PROFILE_INFO )
					{
						int res_status = pack.getDataRangeInt( 9, 1 );
						try
						{
							jsonObject.put( JsonTag.STRING_PROFILE_NAME, profile_name );
							jsonObject.put( JsonTag.INT_PROFILE_RES_STATUS, res_status );
							if(res_status == 0x00)
							{
								int total = pack.getDataRangeInt( 10, 2 );
								int sector_size = pack.getDataRangeInt( 12, 2 );
								int use = pack.getDataRangeInt( 14, 2 );
								int key = pack.getDataRangeInt( 16, 2 );
								jsonObject.put( JsonTag.INT_PROFILE_INFO_TOTAL_SECTOR_COUNT, total );
								jsonObject.put( JsonTag.INT_PROFILE_INFO_SECTOR_SIZE, sector_size );
								jsonObject.put( JsonTag.INT_PROFILE_INFO_USE_SECTOR_COUNT, use );
								jsonObject.put( JsonTag.INT_PROFILE_INFO_USE_KEY_COUNT, key );
							}
						}
						catch ( JSONException e )
						{
							e.printStackTrace();
						}
						btConnection.onCreateMsg( new PenMsg( PenMsgType.PROFILE_INFO, jsonObject ) );
					}
					else if ( res_type == PROFILE_READ_VALUE )
					{
						if(requestOnDefaultCalibration)
						{
							requestOnDefaultCalibration = false;

							try
							{
								int count = pack.getDataRangeInt( 9, 1 );
								int data_offset = 0;
								data_offset = 10;
								for(int i = 0; i < count; i++)
								{
									String key = pack.getDataRangeString( data_offset, 16 ).trim();
									data_offset += 16;
									int key_status = pack.getDataRangeInt( data_offset, 1 );
									data_offset++;
									int key_size = pack.getDataRangeInt( data_offset, 2 );
									data_offset += 2;
									byte[] data = pack.getDataRange(data_offset, key_size );
									data_offset += key_size;
									if(key.equals( KEY_DEFAULT_CALIBRATION ))
									{
										if(key_status == PROFILE_STATUS_SUCCESS)
										{
											ByteBuffer buffer = ByteBuffer.allocate( data.length ).put( data );
											buffer.order( ByteOrder.LITTLE_ENDIAN );
											buffer.rewind();
											int pointCount = (int)buffer.get();
											int[] ret = new int[pointCount*2];
											for(int j =0; j < pointCount *2;j++)
											{
												ret[j] = buffer.getShort();
												NLog.d( "KEY_DEFAULT_CALIBRATION ret["+j+"]");
											}
											reqCalibrate2(PenProfile.getCalibrateFactor(ret[0],ret[1],ret[2],ret[3],ret[4],ret[5]));
										}
										else
										{
											NLog.d( "KEY_DEFAULT_CALIBRATION key_status" +key_status);
										}
										break;
									}
								}
								JSONObject job2 = new JSONObject()
										.put( JsonTag.STRING_PEN_MAC_ADDRESS, btConnection.getMacAddress() )
										.put( JsonTag.STRING_PEN_PASSWORD, currentPassword );
								btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_AUTHORIZED, job2 ) );


							}
							catch ( JSONException e )
							{
								e.printStackTrace();
							}
						}
						else
						{
							try
							{
								jsonObject.put( JsonTag.STRING_PROFILE_NAME, profile_name );
								int count = pack.getDataRangeInt( 9, 1 );
								jsonArray = new JSONArray( );
								int data_offset = 0;
								data_offset = 10;
								for(int i = 0; i < count; i++)
								{
									String key = pack.getDataRangeString( data_offset, 16 ).trim();
									data_offset += 16;
									int key_status = pack.getDataRangeInt( data_offset, 1 );
									data_offset++;
									int key_size = pack.getDataRangeInt( data_offset, 2 );
									data_offset += 2;
									byte[] data = pack.getDataRange(data_offset, key_size );
									data_offset += key_size;
									jsonArray.put( new  JSONObject().put(JsonTag.STRING_PROFILE_KEY, key  ).put( JsonTag.INT_PROFILE_RES_STATUS, key_status ).put( JsonTag.BYTE_PROFILE_VALUE, Base64.encodeToString(data ,Base64.DEFAULT)) );
								}

								jsonObject.put( JsonTag.ARRAY_PROFILE_RES, jsonArray);
							}
							catch ( JSONException e )
							{
								e.printStackTrace();
							}
							btConnection.onCreateMsg( new PenMsg( PenMsgType.PROFILE_READ_VALUE, jsonObject ) );
						}
					}
					else if ( res_type == PROFILE_WRITE_VALUE )
					{
						try
						{
							jsonObject.put( JsonTag.STRING_PROFILE_NAME, profile_name );
							int count = pack.getDataRangeInt( 9, 1 );
							jsonArray = new JSONArray( );
							int data_offset = 0;
							data_offset = 10;
							for(int i = 0; i < count; i++)
							{
								String key = pack.getDataRangeString( data_offset, 16 ).trim();
								data_offset += 16;
								int key_status = pack.getDataRangeInt( data_offset, 1 );
								data_offset++;
								jsonArray.put( new  JSONObject().put(JsonTag.STRING_PROFILE_KEY, key  ).put( JsonTag.INT_PROFILE_RES_STATUS, key_status ) );
							}
							jsonObject.put( JsonTag.ARRAY_PROFILE_RES, jsonArray);
						}
						catch ( JSONException e )
						{
							e.printStackTrace();
						}
						btConnection.onCreateMsg( new PenMsg( PenMsgType.PROFILE_WRITE_VALUE, jsonObject ) );

					}
					else if ( res_type == PROFILE_DELETE_VALUE )
					{
						try
						{
							jsonObject.put( JsonTag.STRING_PROFILE_NAME, profile_name );
							int count = pack.getDataRangeInt( 9, 1 );
							jsonArray = new JSONArray( );
							int data_offset = 0;
							data_offset = 10;
							for(int i = 0; i < count; i++)
							{
								String key = pack.getDataRangeString( data_offset, 16 ).trim();
								data_offset += 16;
								int key_status = pack.getDataRangeInt( data_offset, 1 );
								data_offset++;
								jsonArray.put( new  JSONObject().put(JsonTag.STRING_PROFILE_KEY, key  ).put( JsonTag.INT_PROFILE_RES_STATUS, key_status ) );
							}
							jsonObject.put( JsonTag.ARRAY_PROFILE_RES, jsonArray);
						}
						catch ( JSONException e )
						{
							e.printStackTrace();
						}
						btConnection.onCreateMsg( new PenMsg( PenMsgType.PROFILE_DELETE_VALUE, jsonObject ) );
					}
				}
				else
				{
					if(requestOnDefaultCalibration)
					{
						requestOnDefaultCalibration = false;

						NLog.d( "KEY_DEFAULT_CALIBRATION PROFILE_FAILURE");
						try
						{

							JSONObject job2 = new JSONObject()
									.put( JsonTag.STRING_PEN_MAC_ADDRESS, btConnection.getMacAddress() )
									.put( JsonTag.STRING_PEN_PASSWORD, currentPassword );
							btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_AUTHORIZED, job2 ) );
						}
						catch ( JSONException e )
						{
							e.printStackTrace();
						}
					}
					else
					{
						btConnection.onCreateMsg( new PenMsg( PenMsgType.PROFILE_FAILURE ) );
					}
				}
			}
			break;

		}
	}

	private JSONArray offlineNoteInfos = new JSONArray();
	private JSONArray offlinePageInfos = new JSONArray();

	private void processDot( int sectionId, int ownerId, int noteId, int pageId, long timeLong, int x, int y, int fx, int fy, int force, int type, int color ,int tiltX, int tiltY , int  twist, int penTipType)
	{
		// Down scale from maxPressValue to 256
		force = (force * 255)/maxPress ;
		Fdot tempFdot = new Fdot((x + (float) (fx * 0.01)), (y + (float) (fy * 0.01)), force, type, timeLong, sectionId, ownerId, noteId, pageId, color,penTipType, tiltX, tiltY, twist);

		if ( noteId == 45 && pageId == 1 )
		{
			dotFilterFilm.put( tempFdot );
		}
		else
		{
			dotFilterPaper.put( tempFdot );
		}
	}

	@Override
	public void onFilteredDot( Fdot dot )
	{
		if(factor != null)
			dot.pressure = (int)factor[dot.pressure];
		btConnection.onCreateDot( dot );
	}

	@Override
	public void onCreatePacket( Packet packet )
	{
		parsePacket( packet );
	}


	/**
	 * Pen Info request
	 *
	 */
	public void reqPenInfo()
	{
		NLog.d( "startConnect reqPenInfo" );

//		penInfoRequestCount++;
//		if(penInfoRequestCount > 2)
//		{
//			//
//			return;
//		}

        if(penInfoReceiveCount++ < PENINFO_SEND_FAIL_TRY_COUNT) {
            mHandler.postDelayed(mChkPenInfoFailRunnable, PENINFO_SEND_FAIL_TIME);
        }
        else {
            penInfoReceiveCount = 0;
            mHandler.removeCallbacks(mChkPenInfoFailRunnable);
        }

		write( ProtocolParser20.buildReqPenInfo( appVer ) );

	}

	/**
	 * Pen RTC Setting
	 */
	public void reqSetCurrentTime ()
	{
		int k = (CMD20.REQ_PenStatusChange << 8) & 0xff00 + CMD20.REQ_PenStatusChange_TYPE_CurrentTimeSet ;
		execute( new SetTimeCommand( k, this ) );
	}

	/**
	 * Pen status request
	 */
	public void reqPenStatus()
	{
		write( ProtocolParser20.buildPenStatusData() );
	}

	/**
	 * deprecated from 2.0
	 * force calibrate request
	 */
	public void reqForceCalibrate()
	{
//		execute( new ForceCalibrateCommand( CMD20.P_ForceCalibrate, this ) );
	}

	public void reqSetAutoShutdownTime( short min )
	{
		write( ProtocolParser20.buildAutoShutdownTimeSetup( min ) );
	}

	/**
	 * Req set pen cap on off.
	 *
	 * @param on the on
	 */
	public void reqSetPenCapOnOff( boolean on )
	{
		write( ProtocolParser20.buildPenCapOnOffSetup( on ) );
	}



	public void reqSetPenSensitivity( short sensitivity )
	{
		if(sensorType != 0)
		{
			btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_SETUP_SENSITIVITY_NOT_SUPPORT_DEVICE) );
		}
		else
			write( ProtocolParser20.buildPenSensitivitySetup( sensitivity ) );
	}

	/**
	 * Req set pen sensitivity fsc.
	 *
	 * @param sensitivity the sensitivity
	 */
	public void reqSetPenSensitivityFSC( short sensitivity )
	{
		if(sensorType != 1)
		{
			btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_SETUP_SENSITIVITY_NOT_SUPPORT_DEVICE) );
		}
		else
			write( ProtocolParser20.buildPenSensitivitySetupFSC( sensitivity ) );
	}

	/**
	 * Req set pen hover.
	 *
	 * @param on the on
	 */
	public void reqSetPenHover( boolean on )
	{
		write( ProtocolParser20.buildPenHoverSetup( on ) );
	}


	private static int[][] chunkArray( int[] array, int chunkSize )
	{
		int numOfChunks = (int) Math.ceil( (double) array.length / chunkSize );
		int[][] output = new int[numOfChunks][];

		for ( int i = 0; i < numOfChunks; ++i )
		{
			int start = i * chunkSize;
			int length = Math.min( array.length - start, chunkSize );

			int[] temp = new int[length];
			System.arraycopy( array, start, temp, 0, length );
			output[i] = temp;
		}

		return output;
	}
	
	public void reqAddUsingNote( int sectionId, int ownerId, int[] noteIds )
	{
		UseNoteData data = new UseNoteData();
		data.sectionId = sectionId;
		data.ownerId = ownerId;
		data.noteIds = noteIds;
		ArrayList<UseNoteData> list = new ArrayList<UseNoteData>();
		list.add( data );
		reqAddUsingNote( list );
	}
	
	public void reqAddUsingNote( int sectionId, int ownerId )
	{
		write( ProtocolParser20.buildAddUsingNotes( sectionId, ownerId ) );
	}

	@Override
	public void reqAddUsingNote(int[] sectionId, int[] ownerId) {
		write( ProtocolParser20.buildAddUsingNotes( sectionId, ownerId ) );

	}

	public void reqAddUsingNoteAll()
	{
		write(ProtocolParser20.buildAddUsingAllNotes());
	}

	/**
	 * Req add using note.
	 *
	 * @param noteList the note list
	 */
	public void reqAddUsingNote ( ArrayList<UseNoteData> noteList )
	{

		write(ProtocolParser20.buildAddUsingNotes( noteList ));
	}

	public void reqOfflineData( int sectionId, int ownerId, int noteId )
	{
		write( ProtocolParser20.buildReqOfflineData( sectionId, ownerId, noteId ) );
	}

	/**
	 * Req offline data.
	 *
	 * @param sectionId the section id
	 * @param ownerId   the owner id
	 * @param noteId    the note id
	 * @param pageIds   the page ids
	 */
	public void reqOfflineData( int sectionId, int ownerId, int noteId, int[] pageIds )
	{
		write( ProtocolParser20.buildReqOfflineData( sectionId, ownerId, noteId, pageIds ) );
	}



	public void reqOfflineDataList()
	{
		write( ProtocolParser20.buildReqOfflineDataListAll() );
	}

	/**
	 * Req offline data list.
	 *
	 * @param sectionId the section id
	 * @param ownerId   the owner id
	 */
	public void reqOfflineDataList( int sectionId, int ownerId )
	{
		write( ProtocolParser20.buildReqOfflineDataList( sectionId, ownerId ) );
	}

	/**
	 * Req offline data page list.
	 *
	 * @param sectionId the section id
	 * @param ownerId   the owner id
	 * @param noteId    the note id
	 */
	public void reqOfflineDataPageList( int sectionId, int ownerId, int noteId )
	{
		write( ProtocolParser20.buildReqOfflineDataPageList( sectionId, ownerId, noteId ) );
	}

	/**
	 * Req offline data remove.
	 *
	 * @param sectionId the section id
	 * @param ownerId   the owner id
	 * @param noteIds   the note ids
	 */
	public void reqOfflineDataRemove( int sectionId, int ownerId, int[] noteIds)
	{
		write( ProtocolParser20.buildReqOfflineDataRemove( sectionId, ownerId, noteIds ) );
	}

	public void reqAutoPowerSetupOnOff( boolean isOn )
	{
		write( ProtocolParser20.buildPenAutoPowerSetup( isOn ) );
	}

	public void reqPenBeepSetup( boolean isOn )
	{
		write( ProtocolParser20.buildPenBeepSetup( isOn ) );
	}

	public void reqSetupPenTipColor( int color )
	{
		write( ProtocolParser20.buildPenTipColorSetup( color ) );
	}

	/**
	 * Req setup pen offline data save.
	 *
	 * @param save the save
	 */
	public void reqSetupPenOfflineDataSave( boolean save )
	{
		write( ProtocolParser20.buildPenOfflineDataSaveSetup( save ) );
	}




	public void reqInputPassword( String password )
	{
		if ( password.equals( "0000" ) )
		{
			btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_ILLEGAL_PASSWORD_0000) );
			return;
		}
		currentPassword = password;
		write( ProtocolParser20.buildPasswordInput( password ) );
	}

	public void reqSetUpPassword( String oldPassword, String newPassword )
	{
		if ( newPassword.equals( "0000" ) )
		{
			btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_ILLEGAL_PASSWORD_0000) );
			return;
		}

		this.newPassword = newPassword;
		boolean use = true;
		if(newPassword.length() == 0)
			use = false;
		write( ProtocolParser20.buildPasswordSetup( use, oldPassword, newPassword ) );
	}

	/**
	 * Req set up password off.
	 *
	 * @param oldPassword the old password
	 */
	public void reqSetUpPasswordOff( String oldPassword )
	{
		write( ProtocolParser20.buildPasswordSetup( false, oldPassword, "" ) );
	}

	/**
	 * Req pen sw upgrade.
	 *
	 * @param source     the source
	 * @param fwVersion  the fw version
	 * @param isCompress the is compress
	 */
	public void reqPenSwUpgrade( File source, String fwVersion , boolean isCompress)
	{
		NLog.d( "[CommProcessor20] request pen firmware upgrade." );

		if ( isUpgrading )
		{
			NLog.e( "[CommProcessor20] Upgrade task is still excuting." );
			return;
		}

		isUpgrading = true;
		isUpgradingSuspended = false;

		FwUpgradeCommand20 command = new FwUpgradeCommand20( CMD20.REQ_PenFWUpgrade, this );
		command.setInfo( source, fwVersion, connectedDeviceName , isCompress);
		execute( command );
	}

	public void reqSuspendPenSwUpgrade()
	{
		if ( !isUpgrading )
		{
			NLog.e( "[CommProcessor20] Upgrade task is not excuting." );
			return;
		}
		
		btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_FW_UPGRADE_SUSPEND ) );
		
		isUpgrading = false;
		isUpgradingSuspended = true;
		
		kill( CMD20.REQ_PenFWUpgrade );
		
		chunk = null;
	}

	/**
	 * Gets connect device name.
	 *
	 * @return the connect device name
	 */
	public String getConnectDeviceName()
	{
		return connectedDeviceName;
	}

	/**
	 * Gets connect sub name.
	 *
	 * @return the connect sub name
	 */
	public String getConnectSubName()
	{
		return connectedSubName;
	}




	@Override
	public boolean isSupportPenProfile ()
	{
		String[] temp = receiveProtocolVer.split( "\\." );
		float ver = 0f;
		try
		{
			ver = Float.parseFloat( temp[0]+"."+temp[1] );
		}catch ( Exception e )
		{
			e.printStackTrace();
		}
		if(ver >= PEN_PROFILE_SUPPORT_PROTOCOL_VERSION)
			return true;
		else
			return false;
	}

	/**
	 * Request default calibration.
	 */
	public void requestDefaultCalibration()
	{
		String proFileName = "neolab";
		String[] keys = {KEY_DEFAULT_CALIBRATION };
		readProfileValue ( proFileName, keys );
	}


	@Override
	public void createProfile ( String proFileName, byte[] password )
	{
		write( ProtocolParser20.buildProfileCreate(proFileName, password ) );

	}

	@Override
	public void deleteProfile ( String proFileName, byte[] password )
	{
		write( ProtocolParser20.buildProfileDelete(proFileName, password ) );

	}

	@Override
	public void writeProfileValue ( String proFileName, byte[] password ,String[] keys, byte[][] data )
	{
		write( ProtocolParser20.buildProfileWriteValue(proFileName, password ,keys ,data) );

	}

	@Override
	public void readProfileValue ( String proFileName, String[] keys )
	{
		write( ProtocolParser20.buildProfileReadValue(proFileName, keys ) );

	}

	@Override
	public void deleteProfileValue ( String proFileName, byte[] password, String[] keys )
	{
		write( ProtocolParser20.buildProfileDeleteValue(proFileName, password, keys) );

	}

	@Override
	public void getProfileInfo ( String proFileName )
	{
		write( ProtocolParser20.buildProfileInfo(proFileName) );

	}

	/**
	 * Gets press sensor type.
	 *
	 * @return the press sensor type
	 */
	public int getPressSensorType()
	{

		return sensorType;
	}

	/**
	 * Responding to a request to send a pen SW from a pen
	 *
	 * @param offset the offset
	 * @param status the status
	 */
	public void resPenSwRequest( int offset , int status)
	{
		if ( chunk == null )
		{
			NLog.e( "[CommProcessor20] pen upgrade job has not been initialized." );
			return;
		}

		rQueue.add( new FwPacketInfo( offset, status ) );
	}

	/**
	 * Res pen sw upg status.
	 *
	 * @param status the status
	 */
	public void resPenSwUpgStatus( int status)
	{

	}

	/**
	 * Processing according to pen SW upgrade status
	 *
	 * @param offset the offset
	 * @param status the status
	 */
	public void resPenSwUpgStatus( int offset , int status)
	{
		switch ( status )
		{
			case FwPacketInfo.STATUS_START:case FwPacketInfo.STATUS_CONTINUE:case FwPacketInfo.STATUS_END:
				NLog.d( "[CommProcessor20] received pen upgrade status : "+status );
				fwPacketRetryCount = 0;
				resPenSwRequest( offset, status);
				break;
			case FwPacketInfo.STATUS_ERROR:
				NLog.e( "[CommProcessor20] received pen upgrade status : fw error, fail !!");
//				if(fwPacketRetryCount == 1)
//				{
//					fwPacketRetryCount = 0;
//					finishUpgrade();
//					btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_FW_UPGRADE_FAILURE) );
//					return;
//				}
//				fwPacketRetryCount++;
				fwPacketRetryCount = 0;
				resPenSwRequest( offset, status);
				break;

//			case 0x02:
//				fwPacketRetryCount = 0;
//				NLog.d( "[CommProcessor20] received pen upgrade status : upgrade complete." );
//				finishUpgrade();
//				btConnection.onCreateMsg( new PenMsg( PenMsgType.PEN_FW_UPGRADE_SUCCESS ) );
//				break;


			default:
				fwPacketRetryCount = 0;
				NLog.e( "[CommProcessor20] received pen upgrade status : unknown" );
				finishUpgrade();
				break;
		}
	}

	public void finishUpgrade()
	{
		isUpgrading = false;
		isUpgradingSuspended = false;
		chunk = null;
		
		kill( CMD20.REQ_PenFWUpgrade );
	}

	/**
	 * Gets chunk.
	 *
	 * @return the chunk
	 */
	public Chunk getChunk()
	{
		return this.chunk;
	}

	public void setChunk( Chunk chunk )
	{
		this.chunk = chunk;
	}

	/**
	 * The type Fw packet info.
	 */
	public class FwPacketInfo
	{
		/**
		 * The constant STATUS_START.
		 */
		public static final int STATUS_START = 0;
		/**
		 * The constant STATUS_CONTINUE.
		 */
		public static final int STATUS_CONTINUE = 1;
		/**
		 * The constant STATUS_END.
		 */
		public static final int STATUS_END = 2;
		/**
		 * The constant STATUS_ERROR.
		 */
		public static final int STATUS_ERROR = 3;

		/**
		 * The Offset.
		 */
		public int offset, /**
	 * The Status.
	 */
	status;

		private FwPacketInfo()
		{

		}

		/**
		 * Instantiates a new Fw packet info.
		 *
		 * @param offset the offset
		 * @param status the status
		 */
		public FwPacketInfo(int offset,int status)
		{
			this.offset = offset;
			this.status = status;
		}

	}
}
