package ru.leymooo.botfilter;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import lombok.EqualsAndHashCode;
import net.md_5.bungee.BungeeCord;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.Util;
import net.md_5.bungee.netty.ChannelWrapper;
import net.md_5.bungee.protocol.Protocol;
import net.md_5.bungee.protocol.packet.Chat;
import net.md_5.bungee.protocol.packet.ClientSettings;
import net.md_5.bungee.protocol.packet.KeepAlive;
import ru.leymooo.botfilter.BotFilter.CheckState;
import ru.leymooo.botfilter.caching.PacketUtils;
import ru.leymooo.botfilter.caching.PacketUtils.KickType;
import ru.leymooo.botfilter.utils.IPUtils;
import ru.leymooo.botfilter.utils.ManyChecksUtils;

/**
 *
 * @author Leymooo
 */
@EqualsAndHashCode(callSuper = false, of =
{
    "name"
})
public class Connector extends MoveHandler
{

    public static int TOTAL_TICKS = 100;
    private static long TOTAL_TIME = ( TOTAL_TICKS * 50 ) - 100; //TICKS * 50MS

    public UserConnection userConnection;

    public String name;

    public int version;
    private int aticks = 0, sentPings = 0, captchaAnswer, attemps = 3;
    public long joinTime = System.currentTimeMillis(), lastSend = 0, totalping = 9999;
    public boolean markDisconnected = false;

    public CheckState state = BotFilter.getInstance().getCurrentCheckState();
    public Channel channel;

    private ThreadLocalRandom random = ThreadLocalRandom.current();

    public Connector(UserConnection userConnection)
    {
        this.name = userConnection.getName();
        this.channel = userConnection.getCh().getHandle();
        this.userConnection = userConnection;
        this.version = userConnection.getPendingConnection().getVersion();
        this.userConnection.setClientEntityId( 0 );
        this.userConnection.setDimension( 0 );
        this.userConnection.getCh().setDecoderProtocol( Protocol.BotFilter );
        BotFilter.getInstance().incrementBotCounter();
        ManyChecksUtils.IncreaseOrAdd( IPUtils.getAddress( this.userConnection ) );
        if ( state == CheckState.CAPTCHA_ON_POSITION_FAILED )
        {
            PacketUtils.spawnPlayer( channel, userConnection.getPendingConnection().getVersion(), false, false );
            PacketUtils.titles[0].writeTitle( channel, version );
        } else
        {
            PacketUtils.spawnPlayer( channel, userConnection.getPendingConnection().getVersion(), state == CheckState.ONLY_CAPTCHA, true );
            sendCaptcha();
            PacketUtils.titles[1].writeTitle( channel, version );
        }
        sendPing();
        BotFilter.getInstance().addConnection( this );
        BungeeCord.getInstance().getLogger().log( Level.INFO, "[{0}] <-> BotFilter has connected", name );
    }

    @Override
    public void exception(Throwable t) throws Exception
    {
        markDisconnected = true;
        if ( state == CheckState.FAILED )
        {
            channel.close();
        } else
        {
            this.userConnection.disconnect( Util.exception( t ) );
        }
        disconnected();
    }

    @Override
    public void disconnected(ChannelWrapper channel) throws Exception
    {
        BotFilter.getInstance().removeConnection( null, this );
        disconnected();
    }

    @Override
    public void handlerChanged()
    {
        disconnected();
    }

    private void disconnected()
    {
        channel = null;
        userConnection = null;
    }

    public void completeCheck()
    {
        if ( System.currentTimeMillis() - joinTime < TOTAL_TIME && state != CheckState.ONLY_CAPTCHA )
        {
            if ( state == CheckState.CAPTCHA_POSITION && aticks < TOTAL_TICKS )
            {
                channel.writeAndFlush( PacketUtils.packets[7].get( version ), channel.voidPromise() );
                state = CheckState.ONLY_POSITION;
            } else
            {
                failed( KickType.NOTPLAYER, "Too fast check passed" );
            }
            return;
        }
        KickType type = BotFilter.getInstance().checkIpAddress( IPUtils.getAddress( userConnection ),
                (int) ( totalping / ( lastSend == 0 ? sentPings : sentPings - 1 ) ) );
        if ( type != null )
        {
            failed( type, type == KickType.COUNTRY ? "Country is not allowed" : "Big ping" );
            return;
        }
        state = CheckState.SUCCESSFULLY;
        PacketUtils.titles[2].writeTitle( channel, version );
        channel.flush();
        BotFilter.getInstance().removeConnection( null, this );
        channel.writeAndFlush( PacketUtils.packets[13].get( version ), channel.voidPromise() );
        BotFilter.getInstance().saveUser( getName(), IPUtils.getAddress( userConnection ) );
        userConnection.setNeedLogin( false );
        userConnection.getPendingConnection().finishLogin( userConnection );
        markDisconnected = true;
    }

    @Override
    public void onMove()
    {
        if ( lastY == -1 || state == CheckState.FAILED || state == CheckState.SUCCESSFULLY || onGround )
        {
            return;
        }
        if ( state == CheckState.ONLY_CAPTCHA )
        {
            if ( lastY != y && waitingTeleportId == -1 )
            {
                resetPosition( true );
            }
            return;
        }
        if ( formatDouble( lastY - y ) != getSpeed( ticks ) )
        {
            if ( state == CheckState.CAPTCHA_ON_POSITION_FAILED )
            {
                state = CheckState.ONLY_CAPTCHA;
                joinTime = System.currentTimeMillis() + 3500;
                channel.write( PacketUtils.packets[15].get( version ), channel.voidPromise() );
                PacketUtils.titles[1].writeTitle( channel, version );
                resetPosition( true );
                sendCaptcha();
            } else
            {
                failed( KickType.NOTPLAYER, "Failed position check" );
            }
            return;
        }
        if ( y <= 60 && state == CheckState.CAPTCHA_POSITION && waitingTeleportId == -1 )
        {
            resetPosition( false );
        }
        if ( aticks >= TOTAL_TICKS && state != CheckState.CAPTCHA_POSITION )
        {
            completeCheck();
            return;
        }
        if ( state == CheckState.CAPTCHA_ON_POSITION_FAILED )
        {
            ByteBuf expBuf = PacketUtils.expPacket.get( aticks, version );
            if ( expBuf != null )
            {
                channel.writeAndFlush( expBuf, channel.voidPromise() );
            }
        }
        ticks++;
        aticks++;
    }

    private void resetPosition(boolean disableFall)
    {
        if ( disableFall )
        {
            channel.write( PacketUtils.packets[4].get( version ), channel.voidPromise() );
        }
        waitingTeleportId = 9876;
        channel.writeAndFlush( PacketUtils.packets[5].get( version ), channel.voidPromise() );
    }

    @Override
    public void handle(Chat chat) throws Exception
    {
        if ( state != CheckState.CAPTCHA_ON_POSITION_FAILED )
        {
            String message = chat.getMessage();
            if ( message.length() > 256 )
            {
                failed( KickType.NOTPLAYER, "Too long message" );
                return;
            }
            if ( message.replace( "/", "" ).equals( String.valueOf( captchaAnswer ) ) )
            {
                completeCheck();
            } else if ( --attemps != 0 )
            {
                channel.write( attemps == 2 ? PacketUtils.packets[9].get( version ) : PacketUtils.packets[10].get( version ), channel.voidPromise() );
                sendCaptcha();
            } else
            {
                failed( KickType.NOTPLAYER, "Failed captcha check" );
            }
        }
    }

    @Override
    public void handle(ClientSettings settings) throws Exception
    {
        this.userConnection.setSettings( settings );
        this.userConnection.setCallSettingsEvent( true );
    }

    @Override
    public void handle(KeepAlive keepAlive) throws Exception
    {
        if ( keepAlive.getRandomId() == 9876 )
        {
            if ( lastSend == 0 )
            {
                failed( KickType.NOTPLAYER, "Tried send fake ping" );
                return;
            }
            long ping = System.currentTimeMillis() - lastSend;
            totalping = totalping == 9999 ? ping : totalping + ping;
            lastSend = 0;
        }
    }

    public void sendPing()
    {
        if ( this.lastSend == 0 && !( state == CheckState.FAILED || state == CheckState.SUCCESSFULLY ) )
        {
            lastSend = System.currentTimeMillis();
            sentPings++;
            channel.writeAndFlush( PacketUtils.packets[8].get( version ) );
        }
    }

    private void sendCaptcha()
    {
        captchaAnswer = random.nextInt( 100, 999 );
        channel.write( PacketUtils.packets[6].get( version ), channel.voidPromise() );
        channel.writeAndFlush( PacketUtils.captchas.get( version, captchaAnswer ), channel.voidPromise() );
    }

    public String getName()
    {
        return name.toLowerCase();
    }

    public boolean isConnected()
    {
        return userConnection != null && channel != null && !markDisconnected && userConnection.isConnected();
    }

    public void failed(KickType type, String kickMessage)
    {
        state = CheckState.FAILED;
        PacketUtils.kickPlayer( type, Protocol.GAME, userConnection.getCh(), version );
        markDisconnected = true;
        BungeeCord.getInstance().getLogger().log( Level.INFO, "(BF) [{0}] disconnected: ".concat( kickMessage ), name );
    }

    @Override
    public String toString()
    {
        return "[" + name + "] <-> BotFilter";
    }
}