package org.frozenbox.frozenchat.xmpp.jingle;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import android.annotation.SuppressLint;
import android.util.Log;
import org.frozenbox.frozenchat.Config;
import org.frozenbox.frozenchat.entities.Account;
import org.frozenbox.frozenchat.entities.Message;
import org.frozenbox.frozenchat.services.AbstractConnectionManager;
import org.frozenbox.frozenchat.services.XmppConnectionService;
import org.frozenbox.frozenchat.xml.Element;
import org.frozenbox.frozenchat.xmpp.OnIqPacketReceived;
import org.frozenbox.frozenchat.xmpp.jid.InvalidJidException;
import org.frozenbox.frozenchat.xmpp.jid.Jid;
import org.frozenbox.frozenchat.xmpp.jingle.stanzas.JinglePacket;
import org.frozenbox.frozenchat.xmpp.stanzas.IqPacket;

public class JingleConnectionManager extends AbstractConnectionManager {
	private List<JingleConnection> connections = new CopyOnWriteArrayList<>();

	private HashMap<Jid, JingleCandidate> primaryCandidates = new HashMap<>();

	@SuppressLint("TrulyRandom")
	private SecureRandom random = new SecureRandom();

	public JingleConnectionManager(XmppConnectionService service) {
		super(service);
	}

	public void deliverPacket(Account account, JinglePacket packet) {
		if (packet.isAction("session-initiate")) {
			JingleConnection connection = new JingleConnection(this);
			connection.init(account, packet);
			connections.add(connection);
		} else {
			for (JingleConnection connection : connections) {
				if (connection.getAccount() == account
						&& connection.getSessionId().equals(
								packet.getSessionId())
						&& connection.getCounterPart().equals(packet.getFrom())) {
					connection.deliverPacket(packet);
					return;
				}
			}
			IqPacket response = packet.generateRespone(IqPacket.TYPE_ERROR);
			Element error = response.addChild("error");
			error.setAttribute("type", "cancel");
			error.addChild("item-not-found",
					"urn:ietf:params:xml:ns:xmpp-stanzas");
			error.addChild("unknown-session", "urn:xmpp:jingle:errors:1");
			account.getXmppConnection().sendIqPacket(response, null);
		}
	}

	public JingleConnection createNewConnection(Message message) {
		JingleConnection connection = new JingleConnection(this);
		connection.init(message);
		this.connections.add(connection);
		return connection;
	}

	public JingleConnection createNewConnection(final JinglePacket packet) {
		JingleConnection connection = new JingleConnection(this);
		this.connections.add(connection);
		return connection;
	}

	public void finishConnection(JingleConnection connection) {
		this.connections.remove(connection);
	}

	public void getPrimaryCandidate(Account account,
			final OnPrimaryCandidateFound listener) {
		if (Config.NO_PROXY_LOOKUP) {
			listener.onPrimaryCandidateFound(false, null);
			return;
		}
		if (!this.primaryCandidates.containsKey(account.getJid().toBareJid())) {
			String xmlns = "http://jabber.org/protocol/bytestreams";
			final String proxy = account.getXmppConnection()
					.findDiscoItemByFeature(xmlns);
			if (proxy != null) {
				IqPacket iq = new IqPacket(IqPacket.TYPE_GET);
				iq.setAttribute("to", proxy);
				iq.query(xmlns);
				account.getXmppConnection().sendIqPacket(iq,
						new OnIqPacketReceived() {

							@Override
							public void onIqPacketReceived(Account account,
									IqPacket packet) {
								Element streamhost = packet
										.query()
										.findChild("streamhost",
												"http://jabber.org/protocol/bytestreams");
								if (streamhost != null) {
									JingleCandidate candidate = new JingleCandidate(
											nextRandomId(), true);
									candidate.setHost(streamhost
											.getAttribute("host"));
									candidate.setPort(Integer
											.parseInt(streamhost
													.getAttribute("port")));
									candidate
											.setType(JingleCandidate.TYPE_PROXY);
                                    try {
                                        candidate.setJid(Jid.fromString(proxy));
                                    } catch (final InvalidJidException e) {
                                        candidate.setJid(null);
                                    }
                                    candidate.setPriority(655360 + 65535);
									primaryCandidates.put(account.getJid().toBareJid(),
											candidate);
									listener.onPrimaryCandidateFound(true,
											candidate);
								} else {
									listener.onPrimaryCandidateFound(false,
											null);
								}
							}
						});
			} else {
				listener.onPrimaryCandidateFound(false, null);
			}

		} else {
			listener.onPrimaryCandidateFound(true,
					this.primaryCandidates.get(account.getJid().toBareJid()));
		}
	}

	public String nextRandomId() {
		return new BigInteger(50, random).toString(32);
	}

	public void deliverIbbPacket(Account account, IqPacket packet) {
		String sid = null;
		Element payload = null;
		if (packet.hasChild("open", "http://jabber.org/protocol/ibb")) {
			payload = packet
					.findChild("open", "http://jabber.org/protocol/ibb");
			sid = payload.getAttribute("sid");
		} else if (packet.hasChild("data", "http://jabber.org/protocol/ibb")) {
			payload = packet
					.findChild("data", "http://jabber.org/protocol/ibb");
			sid = payload.getAttribute("sid");
		}
		if (sid != null) {
			for (JingleConnection connection : connections) {
				if (connection.getAccount() == account
						&& connection.hasTransportId(sid)) {
					JingleTransport transport = connection.getTransport();
					if (transport instanceof JingleInbandTransport) {
						JingleInbandTransport inbandTransport = (JingleInbandTransport) transport;
						inbandTransport.deliverPayload(packet, payload);
						return;
					}
				}
			}
			Log.d(Config.LOGTAG,
					"couldnt deliver payload: " + payload.toString());
		} else {
			Log.d(Config.LOGTAG, "no sid found in incomming ibb packet");
		}
	}

	public void cancelInTransmission() {
		for (JingleConnection connection : this.connections) {
			if (connection.getJingleStatus() == JingleConnection.JINGLE_STATUS_TRANSMITTING) {
				connection.cancel();
			}
		}
	}
}
